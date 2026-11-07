package so.fox.nodechain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import so.fox.nodechain.p2p.packet.NodePacket;
import so.fox.nodechain.p2p.packet.OpCode;

/**
 * Модульные тесты бинарного пакета NodePacket: кодирование, декодирование и GZIP.
 */
public class NodePacketTest {

  /**
   * Проверяет сериализацию и чтение пакета без сжатия (PLAIN).
   */
  @Test
  public void testPacketEncodeDecodePlain() throws Exception {
    byte[] payload = "Hello NodeChain".getBytes();
    NodePacket pkt = new NodePacket(OpCode.PING, 42, payload);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    pkt.writeTo(baos);

    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    NodePacket decoded = NodePacket.readFrom(bais);

    Assertions.assertEquals(OpCode.PING, decoded.opCode);
    Assertions.assertEquals(42, decoded.requestId);
    Assertions.assertArrayEquals(payload, decoded.payload);
  }

  /**
   * Проверяет автоматическое сжатие пакета алгоритмом GZIP.
   */
  @Test
  public void testPacketCompressionForLargePayload() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append("compressed-payload-blockchain-block-data-chunk-").append(i).append("\n");
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
}
