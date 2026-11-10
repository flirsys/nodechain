package so.fox.nodechain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.p2p.P2pClientManager;
import so.fox.nodechain.p2p.P2pClientManager.HandshakeContext;
import so.fox.nodechain.p2p.P2pClientManager.HandshakeMessage;
import so.fox.nodechain.p2p.PeerConnection;
import so.fox.nodechain.p2p.packet.NodePacket;
import so.fox.nodechain.p2p.packet.OpCode;

// Тесты безопасности P2P-протокола: бинарный фрейминг, защита от GZIP-бомб, SSRF, Slowloris и спуфинга.
@SpringBootTest(properties = {
  "server.port=0",
  "node.p2p.port=9992",
  "node.db.path=test_p2p_security.db",
  "blockchain.auto-mine=false"
})
public class P2pNetworkAndProtocolSecurityTest {

  @Autowired
  private P2pClientManager p2pClientManager;

  @Autowired
  private so.fox.nodechain.config.NodeConfig nodeConfig;

  // Проверяет сериализацию и чтение пакета без сжатия (PLAIN).
  @Test
  public void testPacketEncodeDecodePlain() throws Exception {
    byte[] payload = "Hello Node".getBytes();
    NodePacket pkt = new NodePacket(OpCode.PING, 42, payload);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    pkt.writeTo(baos);

    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    NodePacket decoded = NodePacket.readFrom(bais);

    Assertions.assertEquals(OpCode.PING, decoded.opCode);
    Assertions.assertEquals(42, decoded.requestId);
    Assertions.assertArrayEquals(payload, decoded.payload);
  }

  // Проверяет автоматическое сжатие пакета алгоритмом GZIP при превышении порога.
  @Test
  public void testPacketCompressionForLargePayload() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append("chunk-data-string-sample-for-compression-").append(i).append("\n");
    }
    String large = sb.toString();
    NodePacket pkt = NodePacket.fromJson(OpCode.BLOCKS_DATA, 100, Map.of("data", large));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    pkt.writeTo(baos);
    byte[] frame = baos.toByteArray();

    Assertions.assertTrue(frame.length < large.length());

    ByteArrayInputStream bais = new ByteArrayInputStream(frame);
    NodePacket decoded = NodePacket.readFrom(bais);

    Assertions.assertEquals(OpCode.BLOCKS_DATA, decoded.opCode);
    Assertions.assertEquals(NodePacket.FLAG_GZIP, decoded.flag);
    Map<?, ?> map = decoded.readJson(Map.class);
    Assertions.assertEquals(large, map.get("data"));
  }

  // Проверяет немедленное отклонение неизвестных битовых флагов в заголовке пакета.
  @Test
  public void testUnknownPacketFlagRejection() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    dos.writeShort(NodePacket.MAGIC);
    dos.writeByte(0x7F);
    dos.writeInt(1);
    dos.writeByte(OpCode.PING);
    dos.writeInt(0);
    dos.flush();

    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    Assertions.assertThrows(IOException.class, () -> NodePacket.readFrom(bais));
  }

  // Проверяет отклонение пакета с некорректным MAGIC-числом.
  @Test
  public void testInvalidMagicRejection() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    dos.writeShort(0x1234);
    dos.writeByte(NodePacket.FLAG_PLAIN);
    dos.writeInt(1);
    dos.writeByte(OpCode.PING);
    dos.writeInt(0);
    dos.flush();

    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    Assertions.assertThrows(IOException.class, () -> NodePacket.readFrom(bais));
  }

  // Проверяет защиту от GZIP-бомбы при распаковке пакета (лимит 8 MiB).
  @Test
  public void testGzipBombDecompressionLimit() throws Exception {
    ByteArrayOutputStream compBaos = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(compBaos)) {
      byte[] zeros = new byte[1024 * 1024];
      for (int i = 0; i < 9; i++) {
        gzip.write(zeros);
      }
      gzip.finish();
    }
    byte[] compressed = compBaos.toByteArray();

    ByteArrayOutputStream frameBaos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(frameBaos);
    dos.writeShort(NodePacket.MAGIC);
    dos.writeByte(NodePacket.FLAG_GZIP);
    dos.writeInt(10);
    dos.writeByte(OpCode.BLOCKS_DATA);
    dos.writeInt(compressed.length);
    dos.write(compressed);
    dos.flush();

    ByteArrayInputStream bais = new ByteArrayInputStream(frameBaos.toByteArray());
    Assertions.assertThrows(IOException.class, () -> NodePacket.readFrom(bais));
  }

  // Проверяет запрет создания исходящего пакета с превышением лимита 8 MiB.
  @Test
  public void testOutgoingPacketExceedingLimitRejection() {
    byte[] oversized = new byte[9 * 1024 * 1024];
    Assertions.assertThrows(IllegalArgumentException.class, () -> new NodePacket(OpCode.PING, 1, oversized));
  }

  // Проверяет защиту от атак замедления сети Slowloris через таймаут чтения пакета.
  @Test
  public void testSlowlorisReadTimeoutProtection() {
    InputStream stalledStream = new InputStream() {
      private int count = 0;
      @Override
      public int read() throws IOException {
        count++;
        if (count == 1) return 0x46; // первый байт NodePacket.MAGIC
        if (count == 2) return 0x4F; // второй байт NodePacket.MAGIC
        try {
          Thread.sleep(600);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return 0;
      }
    };

    Assertions.assertThrows(IOException.class, () -> NodePacket.readFrom(new java.io.DataInputStream(stalledStream)));
  }

  // Проверяет блокировку небезопасных IP-адресов для защиты от SSRF-атак.
  @Test
  public void testSsrfIpAddressProtection() {
    Assertions.assertNull(P2pClientManager.resolveSafePeerAddress("224.0.0.1", 9090, nodeConfig));
    Assertions.assertNull(P2pClientManager.resolveSafePeerAddress("240.0.0.1", 9090, nodeConfig));
    Assertions.assertNull(P2pClientManager.resolveSafePeerAddress("0.0.0.0", 9090, nodeConfig));
    Assertions.assertNull(P2pClientManager.resolveSafePeerAddress("invalid...hostname...foo", 9090, nodeConfig));
    // Порт меньше 1024 или больше 65535 отклоняется
    Assertions.assertNull(P2pClientManager.resolveSafePeerAddress("127.0.0.1", 80, nodeConfig));
    Assertions.assertNull(P2pClientManager.resolveSafePeerAddress("127.0.0.1", 70000, nodeConfig));
    // Валидный порт
    Assertions.assertNotNull(P2pClientManager.resolveSafePeerAddress("127.0.0.1", 9090, nodeConfig));
  }

  // Проверяет аутентификацию рукопожатия, валидацию подписи и защиту от replay-атак.
  @Test
  public void testHandshakeAuthenticationAndReplayDefense() {
    KeyPair peerKp = CryptoUtil.generateKeyPair();
    String peerAddr = CryptoUtil.getAddressFromPublicKey(peerKp.getPublic());
    String pubKeyStr = CryptoUtil.encodePublicKey(peerKp.getPublic());
    long now = System.currentTimeMillis();
    String sessionNonce = UUID.randomUUID().toString();
    Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    HandshakeContext ctx = new HandshakeContext("local-id", "foxchain-main-1", now, seenNonces);

    // Неподписанное сообщение
    HandshakeMessage unsignedMsg = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", now, "", "", sessionNonce
    );
    Assertions.assertFalse(P2pClientManager.isValidHandshake(unsignedMsg, ctx));

    // Устаревшее сообщение (timestamp в прошлом > 120s)
    long expiredTime = now - 150_000L;
    String expiredNonce = UUID.randomUUID().toString();
    String expiredData = peerAddr + "|foxchain-main-1|10|" + "0".repeat(64) + "|9090|" + expiredTime + "|" + expiredNonce;
    String expiredSig = CryptoUtil.sign(peerKp.getPrivate(), expiredData);
    HandshakeMessage expiredMsg = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", expiredTime, pubKeyStr, expiredSig, expiredNonce
    );
    Assertions.assertFalse(P2pClientManager.isValidHandshake(expiredMsg, ctx));

    // Валидное сообщение
    String validNonce = UUID.randomUUID().toString();
    String validData = peerAddr + "|foxchain-main-1|10|" + "0".repeat(64) + "|9090|" + now + "|" + validNonce;
    String validSig = CryptoUtil.sign(peerKp.getPrivate(), validData);
    HandshakeMessage validMsg = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", now, pubKeyStr, validSig, validNonce
    );
    Assertions.assertTrue(P2pClientManager.isValidHandshake(validMsg, ctx));

    // Повторное воспроизведение того же nonce блокируется
    Assertions.assertFalse(P2pClientManager.isValidHandshake(validMsg, ctx));
  }

  // Проверяет защиту от Replay-атаки на Handshake и подмену направления INIT / ACK / AUTH.
  @Test
  public void testHandshakeDirectionAndChallengeResponseDefense() {
    KeyPair peerKp = CryptoUtil.generateKeyPair();
    String peerAddr = CryptoUtil.getAddressFromPublicKey(peerKp.getPublic());
    String pubKeyStr = CryptoUtil.encodePublicKey(peerKp.getPublic());
    long now = System.currentTimeMillis();
    String nonce = UUID.randomUUID().toString();
    String challenge = UUID.randomUUID().toString();
    Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    HandshakeContext ctx = new HandshakeContext("local-id", "foxchain-main-1", now, seenNonces);

    // Валидный ACK с челленджем
    HandshakeMessage ackMsgUnsigned = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", now, pubKeyStr, "", nonce, "ACK", "local-id", challenge
    );
    String sig = CryptoUtil.sign(peerKp.getPrivate(), ackMsgUnsigned.toSignableData());
    HandshakeMessage ackMsg = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", now, pubKeyStr, sig, nonce, "ACK", "local-id", challenge
    );
    Assertions.assertTrue(P2pClientManager.isValidHandshake(ackMsg, ctx));

    // Попытка подделать направление (выдать ACK за INIT без переподписания)
    HandshakeMessage replayedAsInit = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", now, pubKeyStr, sig, nonce, "INIT", "local-id", challenge
    );
    Assertions.assertFalse(P2pClientManager.isValidHandshake(replayedAsInit, ctx));
  }

  // Проверяет немедленное закрытие соединения при получении полезных пакетов до рукопожатия.
  @Test
  public void testUnauthenticatedConnectionDropsNonHandshakePackets() throws Exception {
    try (ServerSocket ss = new ServerSocket(0)) {
      int port = ss.getLocalPort();
      try (Socket client = new Socket("127.0.0.1", port);
           Socket server = ss.accept()) {
        PeerConnection conn = new PeerConnection(server, p2pClientManager);
        Assertions.assertNull(conn.remoteNodeId);

        NodePacket ping = new NodePacket(OpCode.PING, 1, new byte[0]);
        p2pClientManager.handlePacket(conn, ping);

        Assertions.assertFalse(conn.isConnected(), "Неаутентифицированное соединение должно закрываться");
      }
    }
  }

  // Проверяет устойчивость P2P rate-limiting при частых синхронизационных запросах.
  @Test
  public void testP2pRateLimitingAllowsSync() throws Exception {
    try (ServerSocket ss = new ServerSocket(0)) {
      int port = ss.getLocalPort();
      try (Socket client = new Socket("127.0.0.1", port);
           Socket server = ss.accept()) {
        PeerConnection conn = new PeerConnection(server, p2pClientManager);
        conn.remoteNodeId = "peer-rate-test";

        P2pClientManager.GetBlocksRequest req = new P2pClientManager.GetBlocksRequest(0, 50);
        NodePacket pkt1 = NodePacket.fromJson(OpCode.GET_BLOCKS, 1, req);
        p2pClientManager.handlePacket(conn, pkt1);
        Assertions.assertTrue(conn.isConnected());

        Thread.sleep(15);
        NodePacket pkt2 = NodePacket.fromJson(OpCode.GET_BLOCKS, 2, req);
        p2pClientManager.handlePacket(conn, pkt2);
        Assertions.assertTrue(conn.isConnected());
      }
    }
  }
}