package so.fox.nodechain.p2p.packet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// Неизменяемый бинарный пакет сетевого протокола со сжатием GZIP и защитой от переполнения
public class NodePacket {

  public static final short MAGIC = 0x4658;
  public static final byte FLAG_PLAIN = 0x00;
  public static final byte FLAG_GZIP = 0x01;
  public static final int MAX_PAYLOAD_SIZE = 8 * 1024 * 1024;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public final byte flag;
  public final int requestId;
  public final byte opCode;
  public final byte[] payload;

  private final byte[] cachedWirePayload;
  private final byte cachedWireFlag;

  // Конструктор пакета с кодом операции, ID запроса и полезной нагрузкой
  public NodePacket(byte opCode, int requestId, byte[] payload) {
    if (payload != null && payload.length > MAX_PAYLOAD_SIZE) {
      throw new IllegalArgumentException("Размер payload превышает лимит: " + payload.length);
    }
    this.opCode = opCode;
    this.requestId = requestId;
    byte[] p = payload != null ? payload.clone() : new byte[0];
    this.payload = p;
    WireCache wc = computeWireCache(p);
    this.cachedWirePayload = wc.data().clone();
    this.cachedWireFlag = wc.flag();
    this.flag = wc.flag();
  }

  private NodePacket(byte opCode, int requestId, byte flag, byte[] payload, byte[] wirePayload, byte wireFlag) {
    this.opCode = opCode;
    this.requestId = requestId;
    this.flag = flag;
    this.payload = payload != null ? payload.clone() : new byte[0];
    this.cachedWirePayload = wirePayload != null ? wirePayload.clone() : new byte[0];
    this.cachedWireFlag = wireFlag;
  }

  private record WireCache(byte[] data, byte flag) {
  }

  // Сериализует объект в JSON и упаковывает в сетевой пакет с проверкой размера
  public static NodePacket fromJson(byte opCode, int requestId, Object data) {
    if (data == null)
      return new NodePacket(opCode, requestId, new byte[0]);
    try {
      byte[] json = MAPPER.writeValueAsBytes(data);
      if (json.length > MAX_PAYLOAD_SIZE)
        return null;
      return new NodePacket(opCode, requestId, json);
    } catch (Exception err) {
      return null;
    }
  }

  // Десериализует JSON payload пакета в указанный класс
  public <T> T readJson(Class<T> clazz) {
    if (payload == null || payload.length == 0 || clazz == null)
      return null;
    try {
      return MAPPER.readValue(payload, clazz);
    } catch (Exception err) {
      return null;
    }
  }

  // Записывает бинарный фрейм пакета в выходной поток сокета с проверкой лимита
  public void writeTo(OutputStream out) throws IOException {
    if (cachedWirePayload.length > MAX_PAYLOAD_SIZE) {
      throw new IOException("Размер исходящего фрейма превышает лимит: " + cachedWirePayload.length);
    }
    DataOutputStream dos = new DataOutputStream(out);
    dos.writeShort(MAGIC);
    dos.writeByte(cachedWireFlag);
    dos.writeInt(requestId);
    dos.writeByte(opCode);
    dos.writeInt(cachedWirePayload.length);
    if (cachedWirePayload.length > 0)
      dos.write(cachedWirePayload);
    dos.flush();
  }

  // Считывает входящий бинарный фрейм пакета из входного потока сокета
  public static NodePacket readFrom(InputStream in) throws IOException {
    DataInputStream dis = new DataInputStream(in);
    short magic = dis.readShort();
    if (magic != MAGIC) throw new IOException("Некорректный magic пакета: " + Integer.toHexString(magic));

    byte flag = dis.readByte();
    if (flag != FLAG_PLAIN && flag != FLAG_GZIP) throw new IOException("Некорректный флаг пакета: " + flag);

    int reqId = dis.readInt();
    byte op = dis.readByte();
    int len = dis.readInt();
    if (len < 0 || len > MAX_PAYLOAD_SIZE) throw new IOException("Некорректная длина пакета: " + len);

    byte[] raw = new byte[len];
    dis.readFully(raw);

    byte[] body = (flag == FLAG_GZIP && len > 0) ? decompressGzip(raw) : raw;
    return new NodePacket(op, reqId, flag, body, raw, flag);
  }

  private static WireCache computeWireCache(byte[] p) {
    if (p.length > 128) {
      byte[] comp = compressGzip(p);
      if (comp != null) return new WireCache(comp, FLAG_GZIP);
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

  private static byte[] decompressGzip(byte[] raw) throws IOException {
    ByteArrayInputStream bais = new ByteArrayInputStream(raw);
    try (GZIPInputStream gzip = new GZIPInputStream(bais); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[4096];
      int read;
      int total = 0;
      while ((read = gzip.read(buf)) != -1) {
        total += read;
        if (total > MAX_PAYLOAD_SIZE) throw new IOException("Распакованный payload превышает лимит");
        baos.write(buf, 0, read);
      }
      return baos.toByteArray();
    }
  }
}
