package so.fox.nodechain.p2p.packet;

// Коды операций бинарного P2P протокола NodeChain.
public final class OpCode {

  private OpCode() {}

  public static final byte HANDSHAKE = 0x01;
  public static final byte HANDSHAKE_ACK = 0x02;
  public static final byte PING = 0x03;
  public static final byte PONG = 0x04;
  public static final byte GET_PEERS = 0x10;
  public static final byte PEERS_LIST = 0x11;
  public static final byte GET_BLOCKS = 0x20;
  public static final byte BLOCKS_DATA = 0x21;
  public static final byte NEW_BLOCK_ANNOUNCE = 0x22;
  public static final byte NEW_TX_ANNOUNCE = 0x30;

  // Проверяет, является ли байт известным кодом операции протокола.
  public static boolean isValid(byte op) {
    return op == HANDSHAKE || op == HANDSHAKE_ACK || op == PING || op == PONG
        || op == GET_PEERS || op == PEERS_LIST || op == GET_BLOCKS || op == BLOCKS_DATA
        || op == NEW_BLOCK_ANNOUNCE || op == NEW_TX_ANNOUNCE;
  }
}

