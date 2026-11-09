package so.fox.nodechain.p2p.packet;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// Неизменяемый бинарный пакет сетевого протокола со сжатием GZIP и защитой от переполнения.
public class NodePacket {

  public static final short MAGIC = 0x4658;
  public static final byte FLAG_PLAIN = 0x00;
  public static final byte FLAG_GZIP = 0x01;
  public static final int MAX_PAYLOAD_SIZE = 4 * 1024 * 1024;
  public static final int MAX_CONTROL_PAYLOAD_SIZE = 64 * 1024;

  private static final ObjectMapper MAPPER = new ObjectMapper()
    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private record WireCache(byte[] data, byte flag) {}

  // Сериализует объект в JSON и упаковывает в сетевой пакет с проверкой размера.
  public static NodePacket fromJson(byte opCode, int requestId, Object data) {
    if (data == null) return new NodePacket(opCode, requestId, new byte[0]);
    try {
      byte[] json = MAPPER.writeValueAsBytes(data);
      if (json.length > MAX_PAYLOAD_SIZE) return null;
      return new NodePacket(opCode, requestId, json);
    } catch (Exception err) {
      return null;
    }
  }

  // Считывает входящий бинарный фрейм пакета из входного потока сокета с проверкой авторизации.
  public static NodePacket readFrom(InputStream in, boolean isAuthenticated) throws IOException {
    DataInputStream dis = new DataInputStream(in);
    int firstByte = dis.read();
    if (firstByte < 0) throw new EOFException("Connection closed by peer");

    byte[] header = new byte[12];
    header[0] = (byte) firstByte;
    readFullyWithTimeout(dis, header, 1, 11, 5_000L);
    ByteBuffer bb = ByteBuffer.wrap(header);

    short magic = bb.getShort();
    if (magic != MAGIC) throw new IOException("Некорректный magic пакета: " + Integer.toHexString(magic));

    byte flag = bb.get();
    if (flag != FLAG_PLAIN && flag != FLAG_GZIP) throw new IOException("Некорректный флаг пакета: " + flag);

    int reqId = bb.getInt();
    byte op = bb.get();
    int len = bb.getInt();

    if (!isAuthenticated && op != OpCode.HANDSHAKE && op != OpCode.HANDSHAKE_ACK) {
      throw new IOException("Неавторизованный опкод пакета: " + op);
    }

    int maxAllowed = switch (op) {
      case OpCode.PING, OpCode.PONG -> 16;
      case OpCode.HANDSHAKE, OpCode.HANDSHAKE_ACK, OpCode.GET_PEERS, OpCode.PEERS_LIST,
           OpCode.NEW_TX_ANNOUNCE, OpCode.GET_BLOCKS -> MAX_CONTROL_PAYLOAD_SIZE;
      case OpCode.BLOCKS_DATA, OpCode.NEW_BLOCK_ANNOUNCE -> MAX_PAYLOAD_SIZE;
      default -> throw new IOException("Неизвестный опкод пакета: " + op);
    };

    if (len < 0 || len > maxAllowed) {
      throw new IOException("Некорректная длина пакета (" + len + " байт) для опкода " + op);
    }

    byte[] raw = new byte[len];
    readFullyWithTimeout(dis, raw, 0, raw.length, 10_000L);

    byte[] body = (flag == FLAG_GZIP && len > 0) ? decompressGzip(raw, maxAllowed) : raw;
    return new NodePacket(op, reqId, flag, body, raw);
  }

  // Считывает входящий бинарный фрейм пакета (для тестов и обратной совместимости).
  public static NodePacket readFrom(InputStream in) throws IOException {
    return readFrom(in, true);
  }

  // Читает заданное количество байт с жестким таймаутом кадра для защиты от Slowloris
  private static void readFullyWithTimeout(DataInputStream dis, byte[] b, int off, int len, long timeoutMs) throws IOException {
    int total = 0;
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (total < len) {
      if (System.currentTimeMillis() > deadline) {
        throw new SocketTimeoutException("Slowloris DoS protection: read timeout exceeded " + timeoutMs + "ms");
      }
      int r = dis.read(b, off + total, len - total);
      if (r < 0) throw new EOFException("Connection closed prematurely by peer");
      total += r;
    }
  }

  private static WireCache computeWireCache(byte[] p) {
    if (p.length > 128) {
      byte[] comp = compressGzip(p);
      if (comp != null && comp.length < p.length) return new WireCache(comp, FLAG_GZIP);
    }
    return new WireCache(p, FLAG_PLAIN);
  }

  private static byte[] compressGzip(byte[] data) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
      gzip.write(data);
      gzip.finish();
      return baos.toByteArray();
    } catch (IOException err) {
      return null;
    }
  }

  private static byte[] decompressGzip(byte[] raw, int maxAllowed) throws IOException {
    ByteArrayInputStream bais = new ByteArrayInputStream(raw);
    try (GZIPInputStream gzip = new GZIPInputStream(bais); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[4096];
      int read;
      int total = 0;
      while ((read = gzip.read(buf)) != -1) {
        total += read;
        if (total > maxAllowed) {
          throw new IOException("Распакованный payload превышает лимит опкода (" + total + " > " + maxAllowed + ")");
        }
        baos.write(buf, 0, read);
      }
      return baos.toByteArray();
    }
  }

  public final byte flag;
  public final int requestId;
  public final byte opCode;
  public final byte[] payload;
  private final byte[] cachedWirePayload;

  // Конструктор пакета с кодом операции, ID запроса и полезной нагрузкой.
  public NodePacket(byte opCode, int requestId, byte[] payload) {
    if (payload != null && payload.length > MAX_PAYLOAD_SIZE) {
      throw new IllegalArgumentException("Размер payload превышает лимит: " + payload.length);
    }
    this.opCode = opCode;
    this.requestId = requestId;
    byte[] p = payload != null ? payload.clone() : new byte[0];
    this.payload = p;
    WireCache wc = computeWireCache(p);
    this.cachedWirePayload = wc.data();
    this.flag = wc.flag();
  }

  private NodePacket(byte opCode, int requestId, byte flag, byte[] payload, byte[] wirePayload) {
    this.opCode = opCode;
    this.requestId = requestId;
    this.flag = flag;
    this.payload = payload != null ? payload : new byte[0];
    this.cachedWirePayload = wirePayload != null ? wirePayload : new byte[0];
  }

  // Десериализует JSON payload пакета в указанный класс.
  public <T> T readJson(Class<T> clazz) {
    if (payload == null || payload.length == 0 || clazz == null) return null;
    try {
      return MAPPER.readValue(payload, clazz);
    } catch (Exception err) {
      return null;
    }
  }

  // Записывает бинарный фрейм пакета в выходной поток сокета с проверкой лимита.
  public void writeTo(OutputStream out) throws IOException {
    if (cachedWirePayload.length > MAX_PAYLOAD_SIZE) {
      throw new IOException("Размер исходящего фрейма превышает лимит: " + cachedWirePayload.length);
    }
    DataOutputStream dos = new DataOutputStream(out);
    dos.writeShort(MAGIC);
    dos.writeByte(flag);
    dos.writeInt(requestId);
    dos.writeByte(opCode);
    dos.writeInt(cachedWirePayload.length);
    if (cachedWirePayload.length > 0) dos.write(cachedWirePayload);
    dos.flush();
  }
}
