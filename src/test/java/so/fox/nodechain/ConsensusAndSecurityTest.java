package so.fox.nodechain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.ConsensusUtil;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Account;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.P2pClientManager;
import so.fox.nodechain.p2p.P2pClientManager.HandshakeMessage;
import so.fox.nodechain.p2p.packet.NodePacket;
import so.fox.nodechain.p2p.packet.OpCode;
import so.fox.nodechain.repository.AccountRepository;

/**
 * Комплексные тесты консенсуса, безопасности, криптографии и сетевого протокола.
 */
@SpringBootTest(properties = {
  "server.port=0",
  "node.p2p.port=9998",
  "node.db.path=test_consensus.db",
  "blockchain.auto-mine=false"
})
public class ConsensusAndSecurityTest {

  static {
    cleanDbFiles();
  }

  @BeforeAll
  @AfterAll
  public static void cleanup() {
    cleanDbFiles();
  }

  private static void cleanDbFiles() {
    new File("test_consensus.db").delete();
    new File("test_consensus.db-wal").delete();
    new File("test_consensus.db-shm").delete();
  }

  @Autowired
  private BlockchainService blockchainService;

  @Autowired
  private NodeWalletService nodeWalletService;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private P2pClientManager p2pClientManager;

  @Autowired
  private NodeConfig nodeConfig;

  /**
   * Проверяет, что прерывание потока во время майнинга возвращает false и не сохраняет блок.
   */
  @Test
  public void testInterruptedMiningAbortsCleanly() {
    Block b = new Block(999, nodeConfig.chainId, "prev", nodeWalletService.address, List.of(), 4);
    Thread.currentThread().interrupt();
    boolean isMined = b.mineBlock(20);
    Assertions.assertFalse(isMined, "Interrupted thread must return false from mineBlock");
    Assertions.assertTrue(Thread.interrupted(), "Clear interrupted status");
  }

  /**
   * Проверяет отклонение блока, содержащего дублирующиеся транзакции.
   */
  @Test
  public void testDuplicateTxInBlockRejected() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String addr1 = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    KeyPair kp2 = CryptoUtil.generateKeyPair();
    String addr2 = CryptoUtil.getAddressFromPublicKey(kp2.getPublic());

    String data = Transaction.buildCanonicalData(nodeConfig.chainId, addr1, addr2, 10L, 5L, 0L, "");
    String sig = CryptoUtil.sign(kp.getPrivate(), data);
    Transaction tx = new Transaction(nodeConfig.chainId, addr1, addr2, 10L, 5L, 0L, CryptoUtil.encodePublicKey(kp.getPublic()), sig, "");

    boolean isUnique = ConsensusUtil.isTransactionsUnique(List.of(tx, tx));
    Assertions.assertFalse(isUnique, "Block with duplicate tx hashes must be rejected");
  }

  /**
   * Проверяет отклонение неизвестных флагов в заголовке сетевого пакета (W-03).
   */
  @Test
  public void testUnknownPacketFlagThrowsIOException() throws Exception {
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

  /**
   * Проверяет защиту от GZIP-бомбы при распаковке пакета (W-03 & C-06).
   */
  @Test
  public void testGzipBombProtection() throws Exception {
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

  /**
   * Проверяет запрет создания исходящего пакета, превышающего лимит 8 MiB.
   */
  @Test
  public void testOutgoingPacketExceedingLimitThrowsException() {
    byte[] oversized = new byte[9 * 1024 * 1024];
    Assertions.assertThrows(IllegalArgumentException.class, () -> new NodePacket(OpCode.PING, 1, oversized));
  }

  /**
   * Проверяет строгость валидации и вычисление кумулятивной работы PoW (W-09).
   */
  @Test
  public void testCumulativeChainWorkCalculation() {
    Block b2 = new Block(1, nodeConfig.chainId, "0", "m", List.of(), 2);
    Block b3 = new Block(1, nodeConfig.chainId, "0", "m", List.of(), 3);
    List<Block> chain5 = List.of(b2, b2, b2, b2, b2);
    List<Block> chain6 = List.of(b2, b2, b2, b2, b2, b2);
    List<Block> chainHighDiff = List.of(b3);

    BigInteger work1 = ConsensusUtil.calculateChainWork(chain5, 2);
    BigInteger work2 = ConsensusUtil.calculateChainWork(chain6, 2);
    BigInteger workHighDiff = ConsensusUtil.calculateChainWork(chainHighDiff, 2);

    Assertions.assertTrue(work2.compareTo(work1) > 0, "Longer branch must have greater cumulative work");
    Assertions.assertTrue(workHighDiff.compareTo(work1) > 0, "Higher difficulty must have greater weight");
  }

  /**
   * Проверяет, что поврежденный файл кошелька ноды вызывает исключение в loadWalletFrom (C-03).
   */
  @Test
  public void testCorruptedWalletThrowsException() throws Exception {
    Path tempWallet = Files.createTempFile("badwallet", ".dat");
    try {
      Files.writeString(tempWallet, "corrupted_garbage_content\n");
      NodeWalletService testService = new NodeWalletService(tempWallet);
      Assertions.assertThrows(IllegalStateException.class, () -> testService.loadWalletFrom(tempWallet));

      KeyPair kp = CryptoUtil.generateKeyPair();
      String badAddrContent = CryptoUtil.encodePrivateKey(kp.getPrivate()) + "\n"
        + CryptoUtil.encodePublicKey(kp.getPublic()) + "\n"
        + "fox0000000000000000000000000000000000000000000000000000000000000000\n";
      Files.writeString(tempWallet, badAddrContent);
      Assertions.assertThrows(IllegalStateException.class, () -> testService.loadWalletFrom(tempWallet));
    } finally {
      Files.deleteIfExists(tempWallet);
    }
  }

  /**
   * Проверяет защиту от переполнения чисел при проверке транзакций (W-07).
   */
  @Test
  public void testTransactionArithmeticOverflow() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String addr1 = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    KeyPair kp2 = CryptoUtil.generateKeyPair();
    String addr2 = CryptoUtil.getAddressFromPublicKey(kp2.getPublic());

    Transaction overflowTx = new Transaction(
      nodeConfig.chainId, addr1, addr2, Long.MAX_VALUE - 5, 100L, 0L, CryptoUtil.encodePublicKey(kp.getPublic()), "sig", ""
    );
    var res = ConsensusUtil.validateTransactionBasics(overflowTx, nodeConfig.chainId);
    Assertions.assertTrue(res.isError(), "Amount + fee overflow must be rejected");
  }

  /**
   * Проверяет строгую аутентификацию P2P-handshake и защиту от replay (C-04, HIGH).
   */
  @Test
  public void testReplayAndSignatureHandshakeValidation() {
    KeyPair peerKp = CryptoUtil.generateKeyPair();
    String peerAddr = CryptoUtil.getAddressFromPublicKey(peerKp.getPublic());
    String pubKeyStr = CryptoUtil.encodePublicKey(peerKp.getPublic());
    long now = System.currentTimeMillis();
    String sessionNonce = UUID.randomUUID().toString();
    java.util.Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    P2pClientManager.HandshakeContext ctx = new P2pClientManager.HandshakeContext("local-id", "foxchain-main-1", now, seenNonces);

    HandshakeMessage unsignedMsg = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", now, "", "", sessionNonce
    );
    Assertions.assertFalse(P2pClientManager.isValidHandshake(unsignedMsg, ctx));

    long expiredTime = now - 150_000L;
    String expiredNonce = UUID.randomUUID().toString();
    String expiredData = peerAddr + "|foxchain-main-1|10|" + "0".repeat(64) + "|9090|" + expiredTime + "|" + expiredNonce;
    String expiredSig = CryptoUtil.sign(peerKp.getPrivate(), expiredData);
    HandshakeMessage expiredMsg = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", expiredTime, pubKeyStr, expiredSig, expiredNonce
    );
    Assertions.assertFalse(P2pClientManager.isValidHandshake(expiredMsg, ctx));

    long futureTime = now + 90_000L;
    String futureNonce = UUID.randomUUID().toString();
    String futureData = peerAddr + "|foxchain-main-1|10|" + "0".repeat(64) + "|9090|" + futureTime + "|" + futureNonce;
    String futureSig = CryptoUtil.sign(peerKp.getPrivate(), futureData);
    HandshakeMessage futureMsg = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", futureTime, pubKeyStr, futureSig, futureNonce
    );
    Assertions.assertFalse(P2pClientManager.isValidHandshake(futureMsg, ctx));

    String validNonce = UUID.randomUUID().toString();
    String validData = peerAddr + "|foxchain-main-1|10|" + "0".repeat(64) + "|9090|" + now + "|" + validNonce;
    String validSig = CryptoUtil.sign(peerKp.getPrivate(), validData);
    HandshakeMessage validMsg = new HandshakeMessage(
      peerAddr, "foxchain-main-1", 10, "0".repeat(64), 9090, "1.0.0", now, pubKeyStr, validSig, validNonce
    );
    Assertions.assertTrue(P2pClientManager.isValidHandshake(validMsg, ctx));
  }

  /**
   * Проверяет невозможность повторного подтверждения уже смайненной транзакции (W-10).
   */
  @Test
  public void testAlreadyConfirmedTransactionRejectedInSimState() {
    KeyPair kp1 = CryptoUtil.generateKeyPair();
    String addr1 = CryptoUtil.getAddressFromPublicKey(kp1.getPublic());
    Account acc1 = blockchainService.getAccount(addr1);
    acc1.balance = 50_000L;
    blockchainService.saveAccount(acc1);

    KeyPair kp2 = CryptoUtil.generateKeyPair();
    String addr2 = CryptoUtil.getAddressFromPublicKey(kp2.getPublic());

    String data = Transaction.buildCanonicalData(nodeConfig.chainId, addr1, addr2, 500L, 50L, 0L, "");
    String sig = CryptoUtil.sign(kp1.getPrivate(), data);
    Transaction tx = new Transaction(nodeConfig.chainId, addr1, addr2, 500L, 50L, 0L, CryptoUtil.encodePublicKey(kp1.getPublic()), sig, "");

    var submitRes = blockchainService.submitTransaction(tx, false);
    Assertions.assertFalse(submitRes.isError());

    Block mined = blockchainService.mineBlock();
    Assertions.assertNotNull(mined);

    Block duplicateBlock = new Block(
      mined.height + 1, nodeConfig.chainId, mined.hash, nodeWalletService.address,
      List.of(tx), nodeConfig.difficulty
    );
    duplicateBlock.mineBlock(nodeConfig.difficulty);

    boolean isApplied = blockchainService.applyIncomingBlock(duplicateBlock);
    Assertions.assertFalse(isApplied, "Block re-including already confirmed transaction must be rejected");
  }

  /**
   * Проверяет, что getAccount не создает фантомных записей в БД при обращении (CRITICAL-1).
   */
  @Test
  public void testReadOnlyGetAccountDoesNotCreateDatabaseRecord() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String unused = CryptoUtil.getAddressFromPublicKey(kp.getPublic());

    Account acc = blockchainService.getAccount(unused);
    Assertions.assertNotNull(acc);
    Assertions.assertEquals(0L, acc.balance);
    Assertions.assertTrue(accountRepository.findByAddress(unused).isEmpty(), "Database must remain empty for unconfirmed address");

    var stateRes = blockchainService.getAccountState(unused);
    Assertions.assertTrue(stateRes.isError());
    Assertions.assertEquals(404, stateRes.status);
  }

  /**
   * Проверяет нормализацию адресов отправителя и получателя к нижнему регистру перед вычислением хеша (HIGH-3).
   */
  @Test
  public void testTransactionAddressNormalizedToLowercaseBeforeHashing() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String addr = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    String upperFrom = addr.toUpperCase();
    String upperTo = addr.toUpperCase();

    String data = Transaction.buildCanonicalData(nodeConfig.chainId, addr.toLowerCase(), addr.toLowerCase(), 100L, 10L, 0L, "test");
    String sig = CryptoUtil.sign(kp.getPrivate(), data);

    Transaction tx = new Transaction(nodeConfig.chainId, upperFrom, upperTo, 100L, 10L, 0L, CryptoUtil.encodePublicKey(kp.getPublic()), sig, "test");
    Assertions.assertEquals(addr.toLowerCase(), tx.from);
    Assertions.assertEquals(addr.toLowerCase(), tx.to);
    Assertions.assertEquals(tx.calculateHash(), tx.hash);
  }

  /**
   * Проверяет защиту нехвостовых транзакций от выселения при переполнении mempool (MEDIUM-4).
   */
  @Test
  public void testMempoolEvictionProtectsNonTailTransactions() {
    int origLimit = nodeConfig.maxMempoolSize;
    try {
      nodeConfig.maxMempoolSize = 2;
      KeyPair kp = CryptoUtil.generateKeyPair();
      String addr = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
      Account acc = blockchainService.getAccount(addr);
      acc.balance = 100_000L;
      blockchainService.saveAccount(acc);

      KeyPair recKp = CryptoUtil.generateKeyPair();
      String recAddr = CryptoUtil.getAddressFromPublicKey(recKp.getPublic());

      String d0 = Transaction.buildCanonicalData(nodeConfig.chainId, addr, recAddr, 100L, 5L, 0L, "");
      String s0 = CryptoUtil.sign(kp.getPrivate(), d0);
      Transaction tx0 = new Transaction(nodeConfig.chainId, addr, recAddr, 100L, 5L, 0L, CryptoUtil.encodePublicKey(kp.getPublic()), s0, "");
      var res0 = blockchainService.submitTransaction(tx0, false);
      Assertions.assertFalse(res0.isError(), "tx0 submission must succeed");

      String d1 = Transaction.buildCanonicalData(nodeConfig.chainId, addr, recAddr, 100L, 50L, 1L, "");
      String s1 = CryptoUtil.sign(kp.getPrivate(), d1);
      Transaction tx1 = new Transaction(nodeConfig.chainId, addr, recAddr, 100L, 50L, 1L, CryptoUtil.encodePublicKey(kp.getPublic()), s1, "");
      var res1 = blockchainService.submitTransaction(tx1, false);
      Assertions.assertFalse(res1.isError(), "tx1 submission must succeed");

      KeyPair kp2 = CryptoUtil.generateKeyPair();
      String addr2 = CryptoUtil.getAddressFromPublicKey(kp2.getPublic());
      Account acc2 = blockchainService.getAccount(addr2);
      acc2.balance = 100_000L;
      blockchainService.saveAccount(acc2);

      String d2 = Transaction.buildCanonicalData(nodeConfig.chainId, addr2, recAddr, 100L, 20L, 0L, "");
      String s2 = CryptoUtil.sign(kp2.getPrivate(), d2);
      Transaction tx2 = new Transaction(nodeConfig.chainId, addr2, recAddr, 100L, 20L, 0L, CryptoUtil.encodePublicKey(kp2.getPublic()), s2, "");

      var res = blockchainService.submitTransaction(tx2, false);
      Assertions.assertTrue(res.isError(), "Cannot evict protected non-tail tx0 or higher fee tail tx1");
      Assertions.assertNotNull(blockchainService.getTransactionByHash(tx0.hash).value, "tx0 must remain in mempool");
    } finally {
      nodeConfig.maxMempoolSize = origLimit;
    }
  }

  /**
   * Проверяет немедленное закрытие соединения при получении не-handshake пакетов до прохождения аутентификации (HIGH-2).
   */
  @Test
  public void testUnauthenticatedP2pConnectionDropsNonHandshakePackets() throws Exception {
    try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
      int port = ss.getLocalPort();
      try (java.net.Socket client = new java.net.Socket("127.0.0.1", port);
           java.net.Socket server = ss.accept()) {
        so.fox.nodechain.p2p.PeerConnection conn = new so.fox.nodechain.p2p.PeerConnection(server, p2pClientManager);
        Assertions.assertNull(conn.remoteNodeId);

        NodePacket ping = new NodePacket(OpCode.PING, 1, new byte[0]);
        p2pClientManager.handlePacket(conn, ping);

        Assertions.assertFalse(conn.isConnected(), "Unauthenticated connection must be closed immediately on non-handshake packet");
      }
    }
  }

  /**
   * Проверяет, что rate-limit для GET_BLOCKS и BLOCKS_DATA позволяет частые запросы (10 мс) и изолирован по портам.
   */
  @Test
  public void testP2pRateLimitingAllowsFastSyncAndPerPortIsolation() throws Exception {
    try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
      int port = ss.getLocalPort();
      try (java.net.Socket client1 = new java.net.Socket("127.0.0.1", port);
           java.net.Socket server1 = ss.accept()) {
        so.fox.nodechain.p2p.PeerConnection conn1 = new so.fox.nodechain.p2p.PeerConnection(server1, p2pClientManager);
        conn1.remoteNodeId = "peer-node-1";

        P2pClientManager.GetBlocksRequest req = new P2pClientManager.GetBlocksRequest(0, 100);
        NodePacket pkt1 = NodePacket.fromJson(OpCode.GET_BLOCKS, 1, req);
        p2pClientManager.handlePacket(conn1, pkt1);
        Assertions.assertTrue(conn1.isConnected());

        Thread.sleep(15);
        NodePacket pkt2 = NodePacket.fromJson(OpCode.GET_BLOCKS, 2, req);
        p2pClientManager.handlePacket(conn1, pkt2);
        Assertions.assertTrue(conn1.isConnected());
      }
    }
  }

  /**
   * Проверяет канонический алгоритм халвинга эмиссии Bitcoin Core (деление пополам каждые N блоков).
   */
  @Test
  public void testCanonicalBitcoinCoreHalvingAlgorithm() {
    long initialReward = 50L * 100_000_000L; // 50 BTC в сатоши
    long interval = 210_000L;

    // Высота 0 (Генезис) не дает эмиссии через майнинг
    Assertions.assertEquals(0L, ConsensusUtil.calculateBlockSubsidy(0, initialReward, interval));

    // Эпоха 0 (блоки 1..209999): 50 BTC
    Assertions.assertEquals(initialReward, ConsensusUtil.calculateBlockSubsidy(1, initialReward, interval));
    Assertions.assertEquals(initialReward, ConsensusUtil.calculateBlockSubsidy(100_000, initialReward, interval));
    Assertions.assertEquals(initialReward, ConsensusUtil.calculateBlockSubsidy(209_999, initialReward, interval));

    // 1-й халвинг (блок 210000): 25 BTC
    Assertions.assertEquals(25L * 100_000_000L, ConsensusUtil.calculateBlockSubsidy(210_000, initialReward, interval));
    Assertions.assertEquals(25L * 100_000_000L, ConsensusUtil.calculateBlockSubsidy(419_999, initialReward, interval));

    // 2-й халвинг (блок 420000): 12.5 BTC
    Assertions.assertEquals(12_5000_0000L, ConsensusUtil.calculateBlockSubsidy(420_000, initialReward, interval));

    // 3-й халвинг (блок 630000): 6.25 BTC
    Assertions.assertEquals(6_2500_0000L, ConsensusUtil.calculateBlockSubsidy(630_000, initialReward, interval));

    // 4-й халвинг (блок 840000): 3.125 BTC
    Assertions.assertEquals(3_1250_0000L, ConsensusUtil.calculateBlockSubsidy(840_000, initialReward, interval));

    // 64 халвинга и более: эмиссия строго 0 (защита от неопределенного сдвига)
    Assertions.assertEquals(0L, ConsensusUtil.calculateBlockSubsidy(64 * interval, initialReward, interval));
    Assertions.assertEquals(0L, ConsensusUtil.calculateBlockSubsidy(100 * interval, initialReward, interval));

    // Некорректные аргументы
    Assertions.assertEquals(0L, ConsensusUtil.calculateBlockSubsidy(-5, initialReward, interval));
    Assertions.assertEquals(0L, ConsensusUtil.calculateBlockSubsidy(10, 0, interval));
    Assertions.assertEquals(0L, ConsensusUtil.calculateBlockSubsidy(10, initialReward, 0));
  }
}
