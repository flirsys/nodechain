package so.fox.nodechain;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import so.fox.nodechain.config.DatabaseConfig;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.core.Result;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Account;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.PeerInfo;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.P2pClientManager;
import so.fox.nodechain.repository.AccountRepository;
import so.fox.nodechain.repository.BlockRepository;
import so.fox.nodechain.repository.PeerRepository;
import so.fox.nodechain.repository.TransactionRepository;

/**
 * Комплексное тестирование взаимодействия двух и более реальных P2P-узлов блокчейна:
 * 1. Двустороннее P2P-рукопожатие и аутентификация RSA.
 * 2. Gossip-распространение неподтвержденных транзакций (Mempool Sync).
 * 3. Майнинг и мгновенное распространение блоков в реальном времени.
 * 4. Fast-Sync (IBD): синхронизация новой ноды с уже продвинутой сетью.
 * 5. Реорганизация цепи (Chain Reorg) при форке по правилу наибольшей кумулятивной работы PoW.
 * 6. Защита от византийских атак: отклонение фальшивых блоков и запрет их ретрансляции.
 * 7. Кластер из трех нод: сквозная ретрансляция транзакций и блоков (Multi-Hop Relay Mesh).
 * 8. Peer Discovery (P2P обмен пирами через GET_PEERS / PEERS_LIST).
 * 9. Устойчивость к обрывам соединения и повторное подключение после сбоев.
 * 10. Защита от чужих блокчейнов (Mismatch Chain ID Handshake Rejection).
 */
public class MultiNodeNetworkTest {

  private static KeyPair sharedGenesisKey;
  private static String sharedGenesisAddr;
  private final List<TestNode> activeNodes = new ArrayList<>();

  @BeforeAll
  public static void setUpSharedGenesis() {
    sharedGenesisKey = CryptoUtil.generateKeyPair();
    sharedGenesisAddr = CryptoUtil.getAddressFromPublicKey(sharedGenesisKey.getPublic());
  }

  @AfterEach
  public void tearDown() {
    for (TestNode node : activeNodes) {
      node.close();
    }
    activeNodes.clear();
  }

  @Test
  @DisplayName("Сценарий 1: Двустороннее P2P-рукопожатие и взаимная аутентификация двух нод")
  public void testMutualHandshakeBetweenTwoNodes() throws Exception {
    TestNode nodeA = createAndStartNode("handshake_a");
    TestNode nodeB = createAndStartNode("handshake_b");

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);

    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 1, 3000);

    Assertions.assertEquals(1, nodeA.p2p.getConnectedPeersCount());
    Assertions.assertEquals(1, nodeB.p2p.getConnectedPeersCount());
    Assertions.assertTrue(nodeA.p2p.isSynced());
    Assertions.assertTrue(nodeB.p2p.isSynced());

    // Попытка подключения ноды к самой себе блокируется
    nodeA.p2p.connectToPeer("127.0.0.1", nodeA.port);
    Thread.sleep(50);
    Assertions.assertEquals(1, nodeA.p2p.getConnectedPeersCount());
  }

  @Test
  @DisplayName("Сценарий 2: Gossip-распространение транзакций (Mempool Synchronization)")
  public void testMempoolTransactionGossipBetweenTwoNodes() throws Exception {
    TestNode nodeA = createAndStartNode("gossip_a");
    TestNode nodeB = createAndStartNode("gossip_b");

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 1, 3000);

    KeyPair recipient = CryptoUtil.generateKeyPair();
    String recipientAddr = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());

    Transaction tx = createSignedTransaction(
      sharedGenesisKey, recipientAddr, 1000L, 50L, 0L, "Hello from Node A"
    );

    // Отправляем транзакцию на Node A
    Result<Transaction> submitRes = nodeA.blockchain.submitTransaction(tx, true);
    Assertions.assertTrue(submitRes.isOk, "Node A должна принять валидную транзакцию: " + submitRes.error + " (code=" + submitRes.status + ")");
    Assertions.assertEquals(1, nodeA.blockchain.getMempoolTransactions().size());

    // Ожидаем gossip-распространения на Node B
    waitForCondition(() -> nodeB.blockchain.getMempoolTransactions().size() == 1, 3000,
      "Node B не получила транзакцию через gossip");

    Transaction received = nodeB.blockchain.getMempoolTransactions().get(0);
    Assertions.assertEquals(tx.hash, received.hash);
    Assertions.assertEquals(1000L, received.amount);
    Assertions.assertEquals("Hello from Node A", received.comment);

    // Проверяем, что неподтвержденный баланс отправителя на Node B корректно уменьшился
    long expectedBal = 1_000_000_000_000L - (1000L + 50L);
    Assertions.assertEquals(expectedBal, nodeB.blockchain.calculateUnconfirmedBalance(sharedGenesisAddr));
  }

  @Test
  @DisplayName("Сценарий 3: Майнинг и мгновенное распространение блоков с очисткой мемпула")
  public void testMinedBlockPropagationAndMempoolCleanup() throws Exception {
    TestNode nodeA = createAndStartNode("mining_a");
    TestNode nodeB = createAndStartNode("mining_b");

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 1, 3000);

    KeyPair recipient = CryptoUtil.generateKeyPair();
    String recipientAddr = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());
    Transaction tx = createSignedTransaction(sharedGenesisKey, recipientAddr, 2500L, 50L, 0L, "Payment for services");

    nodeA.blockchain.submitTransaction(tx, true);
    waitForCondition(() -> nodeB.blockchain.getMempoolTransactions().size() == 1, 3000, "Node B mempool sync");

    // Node A майнит блок #1
    Block minedBlock = nodeA.blockchain.mineBlock();
    Assertions.assertNotNull(minedBlock, "Node A должна успешно намайнить блок");
    Assertions.assertEquals(1, minedBlock.height);

    // Node B должна автоматически получить блок #1 по P2P, проверить PoW и применить его
    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 1L, 3000,
      "Node B не применила блок #1 от Node A");

    Block nodeBLatest = nodeB.blockchain.getLatestBlockHeader();
    Assertions.assertEquals(minedBlock.hash, nodeBLatest.hash);
    Assertions.assertEquals(minedBlock.merkleRoot, nodeBLatest.merkleRoot);

    // Мемпулы обеих нод должны очиститься от подтвержденной транзакции
    Assertions.assertTrue(nodeA.blockchain.getMempoolTransactions().isEmpty());
    Assertions.assertTrue(nodeB.blockchain.getMempoolTransactions().isEmpty());

    // Состояние баланса получателя на Node B должно быть актуализировано
    Account recipientAcc = nodeB.accountRepo.findByAddress(recipientAddr).orElse(null);
    Assertions.assertNotNull(recipientAcc);
    Assertions.assertEquals(2500L, recipientAcc.balance);
  }

  @Test
  @DisplayName("Сценарий 4: Fast-Sync (IBD) — Новая нода подключается и догоняет сеть")
  public void testFastSyncCatchUpNewNodeJoinsAdvancedNetwork() throws Exception {
    TestNode nodeA = createAndStartNode("fastsync_a");

    // Node A майнит 3 блока оффлайн
    for (int i = 0; i < 3; i++) {
      Block b = nodeA.blockchain.mineBlock();
      Assertions.assertNotNull(b);
    }
    Assertions.assertEquals(3L, nodeA.blockchain.getLatestBlockHeader().height);

    // Создаем Node B с нуля (на высоте 0)
    TestNode nodeB = createAndStartNode("fastsync_b");
    Assertions.assertEquals(0L, nodeB.blockchain.getLatestBlockHeader().height);

    // Подключаем Node B к Node A
    nodeB.p2p.connectToPeer("127.0.0.1", nodeA.port);
    waitForPeers(nodeB.p2p, 1, 3000);

    // Node B должна обнаружить отставание по высоте, запросить GET_BLOCKS и догнать до высоты 3
    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 3L, 4000,
      "Node B не синхронизировала 3 блока через Fast-Sync");

    Assertions.assertEquals(nodeA.blockchain.getLatestBlockHeader().hash,
      nodeB.blockchain.getLatestBlockHeader().hash);
    Assertions.assertTrue(nodeB.p2p.isSynced());
  }

  @Test
  @DisplayName("Сценарий 5: Реорганизация цепи (Chain Reorg) при форке по правилу Longest Chain")
  public void testChainReorganizationBetweenTwoNodesLongestChainRule() throws Exception {
    TestNode nodeA = createAndStartNode("reorg_a");
    TestNode nodeB = createAndStartNode("reorg_b");

    // 1. Обе ноды синхронизируются на общем блоке #1
    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    Block block1 = nodeA.blockchain.mineBlock();
    Assertions.assertNotNull(block1);
    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node B sync block 1");

    // 2. Симулируем разделение сети (Network Partition) — разрываем соединение
    nodeA.p2p.stop();
    nodeB.p2p.stop();

    // 3. Node A в изоляции майнит 1 блок (#2A)
    Block block2A = nodeA.blockchain.mineBlock();
    Assertions.assertEquals(2L, nodeA.blockchain.getLatestBlockHeader().height);

    // 4. Node B в изоляции майнит 2 блока (#2B и #3B) — ветка Node B длиннее и имеет больший PoW
    Block block2B = nodeB.blockchain.mineBlock();
    Block block3B = nodeB.blockchain.mineBlock();
    Assertions.assertEquals(3L, nodeB.blockchain.getLatestBlockHeader().height);
    Assertions.assertNotEquals(block2A.hash, block2B.hash, "Хеши блоков форка должны различаться");

    // 5. Восстанавливаем сеть (перезапускаем P2P)
    nodeA.p2p.start();
    nodeB.p2p.start();
    Thread.sleep(100);

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);

    // 6. Node A должна обнаружить более длинную цепь Node B, выполнить откат #2A и применить ветку #2B..#3B
    waitForCondition(() -> nodeA.blockchain.getLatestBlockHeader().height == 3L, 5000,
      "Node A не выполнила реорганизацию на длинную ветку Node B");

    Assertions.assertEquals(block3B.hash, nodeA.blockchain.getLatestBlockHeader().hash);
    Assertions.assertEquals(block3B.hash, nodeB.blockchain.getLatestBlockHeader().hash);
  }

  @Test
  @DisplayName("Сценарий 6: Защита от византийских атак — отклонение невалидных блоков и запрет ретрансляции")
  public void testMaliciousBlockRejectedAndNotPropagated() throws Exception {
    TestNode nodeHonest1 = createAndStartNode("honest_1");
    TestNode nodeHonest2 = createAndStartNode("honest_2");

    nodeHonest1.p2p.connectToPeer("127.0.0.1", nodeHonest2.port);
    waitForPeers(nodeHonest1.p2p, 1, 3000);
    waitForPeers(nodeHonest2.p2p, 1, 3000);

    // Формируем фальшивый блок: неверный Proof of Work (начинается с "f", а не с нулей "00")
    Block latest = nodeHonest1.blockchain.getLatestBlockHeader();
    Block fakeBlock = new Block(latest.height + 1, latest.hash, "f5ox000000000000000000000000000000000000000000000000000000000000", Collections.emptyList(), 2);
    fakeBlock.hash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    // Отправляем фальшивый блок на Node 1
    boolean isApplied = nodeHonest1.blockchain.applyIncomingBlock(fakeBlock);
    Assertions.assertFalse(isApplied, "Честная нода обязана отклонить блок с невалидным PoW");

    // Высота обеих честных нод не должна измениться
    Thread.sleep(100);
    Assertions.assertEquals(0L, nodeHonest1.blockchain.getLatestBlockHeader().height);
    Assertions.assertEquals(0L, nodeHonest2.blockchain.getLatestBlockHeader().height);
  }

  @Test
  @DisplayName("Сценарий 7: Кластер из трех нод — сквозная gossip-ретрансляция через промежуточный узел")
  public void testThreeNodeLinearRelayGossipMesh() throws Exception {
    TestNode nodeA = createAndStartNode("mesh_a");
    TestNode nodeB = createAndStartNode("mesh_b");
    TestNode nodeC = createAndStartNode("mesh_c");

    // Топология: Node A <-> Node B <-> Node C (Node A и Node C напрямую НЕ соединены)
    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    nodeB.p2p.connectToPeer("127.0.0.1", nodeC.port);

    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 2, 3000);
    waitForPeers(nodeC.p2p, 1, 3000);

    // 1. Проверяем ретрансляцию транзакции: отправляем на A, дойти должна до C через B
    KeyPair recipient = CryptoUtil.generateKeyPair();
    String recipientAddr = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());
    Transaction tx = createSignedTransaction(sharedGenesisKey, recipientAddr, 300L, 50L, 0L, "Relay through B");

    nodeA.blockchain.submitTransaction(tx, true);

    waitForCondition(() -> nodeB.blockchain.getMempoolTransactions().size() == 1, 3000, "Node B relay tx");
    waitForCondition(() -> nodeC.blockchain.getMempoolTransactions().size() == 1, 3000, "Node C relay tx");

    Assertions.assertEquals(tx.hash, nodeC.blockchain.getMempoolTransactions().get(0).hash);

    // 2. Проверяем ретрансляцию блока: майнит Node A, дойти должен до Node C через Node B
    Block mined = nodeA.blockchain.mineBlock();
    Assertions.assertNotNull(mined);

    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node B relay block");
    waitForCondition(() -> nodeC.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node C relay block");

    Assertions.assertEquals(mined.hash, nodeC.blockchain.getLatestBlockHeader().hash);
  }

  @Test
  @DisplayName("Сценарий 8: Peer Discovery (Gossip обмен пирами через GET_PEERS / PEERS_LIST)")
  public void testPeerDiscoveryViaGossip() throws Exception {
    TestNode nodeA = createAndStartNode("disco_a");
    TestNode nodeB = createAndStartNode("disco_b");
    TestNode nodeC = createAndStartNode("disco_c");

    // 1. Подключаем C к B, чтобы Node B зарегистрировала Node C
    nodeC.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeC.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 1, 3000);

    // 2. Теперь подключаем A к B. При завершении рукопожатия Node A запрашивает GET_PEERS
    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 2, 3000);

    // Node A запрашивает список пиров у Node B
    // В ходе рукопожатия Node A автоматически шлет GET_PEERS. Ждем появления записи о Node C в БД Node A
    waitForCondition(() -> {
      List<PeerInfo> known = nodeA.peerRepo.findAll();
      for (PeerInfo p : known) {
        if (p.port() == nodeC.port) return true;
      }
      return false;
    }, 4000, "Node A не обнаружила Node C через Peer Discovery от Node B");

    boolean isFound = nodeA.peerRepo.findAll().stream().anyMatch(p -> p.port() == nodeC.port);
    Assertions.assertTrue(isFound, "Node A должна сохранить порт Node C в таблице peers");
  }

  @Test
  @DisplayName("Сценарий 9: Устойчивость к внезапному обрыву соединения и корректное переподключение")
  public void testNodeDisconnectionAndReconnectResilience() throws Exception {
    TestNode nodeA = createAndStartNode("disc_res_a");
    TestNode nodeB = createAndStartNode("disc_res_b");

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 1, 3000);

    // Обрываем Node B
    nodeB.p2p.stop();
    waitForCondition(() -> nodeA.p2p.getConnectedPeersCount() == 0, 3000, "Node A не зафиксировала обрыв");
    Assertions.assertEquals(0, nodeA.p2p.getConnectedPeersCount());

    // Node A майнит блок #1 в оффлайне
    Block b1 = nodeA.blockchain.mineBlock();
    Assertions.assertNotNull(b1);

    // Перезапускаем Node B на том же порту
    nodeB.p2p.start();
    Thread.sleep(100);

    // Восстанавливаем подключение Node A к Node B
    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);

    // Node B должна успешно синхронизировать намайненный блок #1
    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node B не догнала блок #1");
    Assertions.assertEquals(b1.hash, nodeB.blockchain.getLatestBlockHeader().hash);
  }

  @Test
  @DisplayName("Сценарий 10: Защита от чужих сетей — несовпадающий Chain ID отклоняется при рукопожатии")
  public void testWrongChainIdHandshakeRejected() throws Exception {
    TestNode nodeMain = createAndStartNode("main_chain");

    // Создаем ноду с чужим идентификатором сети (foxchain-testnet)
    int roguePort = findAvailablePort();
    TestNode nodeRogue = new TestNode("rogue_node", roguePort, sharedGenesisAddr);
    nodeRogue.config.chainId = "foxchain-testnet-wrong";
    activeNodes.add(nodeRogue);
    nodeRogue.start();

    // Пытаемся подключить ноду с чужим chainId
    nodeMain.p2p.connectToPeer("127.0.0.1", nodeRogue.port);

    Thread.sleep(200);

    // Рукопожатие должно быть отвергнуто, сокет закрыт, счетчик пиров остается 0
    Assertions.assertEquals(0, nodeMain.p2p.getConnectedPeersCount());
    Assertions.assertEquals(0, nodeRogue.p2p.getConnectedPeersCount());
  }

  // =========================================================================
  // Вспомогательные методы создания и синхронизации тестовых узлов
  // =========================================================================

  private TestNode createAndStartNode(String name) throws Exception {
    int port = findAvailablePort();
    TestNode node = new TestNode(name, port, sharedGenesisAddr);
    activeNodes.add(node);
    node.start();
    return node;
  }

  private static int findAvailablePort() {
    try (ServerSocket ss = new ServerSocket(0)) {
      return ss.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException("Не удалось выделить свободный порт для ноды", e);
    }
  }

  private static void waitForPeers(P2pClientManager p2p, int count, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (p2p.getConnectedPeersCount() >= count) return;
      Thread.sleep(25);
    }
    Assertions.fail("Таймаут ожидания " + count + " подключений. Текущее число: " + p2p.getConnectedPeersCount());
  }

  private static void waitForCondition(ConditionChecker checker, long timeoutMs, String failMsg) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (checker.check()) return;
      Thread.sleep(25);
    }
    Assertions.fail("Таймаут ожидания: " + failMsg);
  }

  @FunctionalInterface
  private interface ConditionChecker {
    boolean check();
  }

  private static Transaction createSignedTransaction(
      KeyPair senderKey, String to, long amount, long fee, long nonce, String comment) {
    return createSignedTransaction("foxchain-main-1", senderKey, to, amount, fee, nonce, comment);
  }

  private static Transaction createSignedTransaction(
      String chainId, KeyPair senderKey, String to, long amount, long fee, long nonce, String comment) {
    String from = CryptoUtil.getAddressFromPublicKey(senderKey.getPublic());
    String pubKey = CryptoUtil.encodePublicKey(senderKey.getPublic());
    String canonical = Transaction.buildCanonicalData(chainId, from, to, amount, fee, nonce, comment);
    String sig = CryptoUtil.sign(senderKey.getPrivate(), canonical);
    return new Transaction(chainId, from, to, amount, fee, nonce, pubKey, sig, comment);
  }

  /**
   * Изолированный автономный инстанс узла блокчейна (собственная SQLite БД, сокет, кошелек и мемпул).
   */
  public static class TestNode implements AutoCloseable {
    public final String name;
    public final int port;
    public final String dbFile;
    public final Path walletFile;
    public final NodeConfig config;
    public final HikariDataSource dataSource;
    public final JdbcTemplate jdbcTemplate;
    public final BlockRepository blockRepo;
    public final TransactionRepository txRepo;
    public final AccountRepository accountRepo;
    public final PeerRepository peerRepo;
    public final NodeWalletService wallet;
    public final BlockchainService blockchain;
    public final P2pClientManager p2p;

    public TestNode(String name, int port, String genesisAddr) {
      this.name = name;
      this.port = port;
      this.dbFile = "test_p2p_" + name + "_" + port + ".db";
      this.walletFile = Path.of("test_p2p_wallet_" + name + "_" + port + ".dat");
      cleanFiles();

      HikariConfig hikariCfg = new HikariConfig();
      hikariCfg.setDriverClassName("org.sqlite.JDBC");
      hikariCfg.setJdbcUrl("jdbc:sqlite:" + dbFile);
      hikariCfg.setMaximumPoolSize(1);
      hikariCfg.setConnectionTimeout(15000);
      this.dataSource = new HikariDataSource(hikariCfg);
      this.jdbcTemplate = new JdbcTemplate(dataSource);
      DatabaseConfig.initDatabase(jdbcTemplate);

      this.blockRepo = new BlockRepository(jdbcTemplate);
      this.txRepo = new TransactionRepository(jdbcTemplate);
      this.accountRepo = new AccountRepository(jdbcTemplate);
      this.peerRepo = new PeerRepository(jdbcTemplate);

      this.wallet = new NodeWalletService(walletFile);
      this.wallet.init();

      this.config = new NodeConfig();
      this.config.dbPath = dbFile;
      this.config.p2pPort = port;
      this.config.chainId = "foxchain-main-1";
      this.config.difficulty = 2;
      this.config.miningReward = 100_000_000L;
      this.config.isAutoMine = false;
      this.config.genesisAllocation = 1_000_000_000_000L;
      this.config.genesisAddress = genesisAddr;
      this.config.maxMempoolSize = 5000;

      PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
      this.blockchain = new BlockchainService(config, blockRepo, txRepo, accountRepo, wallet, txManager, null);
      this.p2p = new P2pClientManager(config, peerRepo, blockchain, wallet);
      this.blockchain.setP2pClientManager(p2p);
    }

    public void start() {
      blockchain.init();
      p2p.start();
    }

    @Override
    public void close() {
      try {
        p2p.stop();
      } catch (Exception ignored) {}
      try {
        blockchain.stop();
      } catch (Exception ignored) {}
      try {
        dataSource.close();
      } catch (Exception ignored) {}
      cleanFiles();
    }

    private void cleanFiles() {
      new File(dbFile).delete();
      new File(dbFile + "-wal").delete();
      new File(dbFile + "-shm").delete();
      try {
        Files.deleteIfExists(walletFile);
      } catch (IOException ignored) {}
    }
  }
}
