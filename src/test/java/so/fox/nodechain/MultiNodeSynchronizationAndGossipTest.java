package so.fox.nodechain;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import so.fox.nodechain.core.Result;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Account;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.PeerInfo;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.P2pClientManager;

// Интеграционные тесты кластера P2P: рукопожатие, сплетни мемпула, Fast-Sync, реорганизация цепи и защита от византийских блоков.
public class MultiNodeSynchronizationAndGossipTest {

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

  // Проверяет взаимное рукопожатие двух нод и защиту от подключения к самой себе.
  @Test
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

  // Проверяет распространение транзакций через gossip и синхронизацию мемпула.
  @Test
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

    Result<Transaction> submitRes = nodeA.blockchain.submitTransaction(tx, true);
    Assertions.assertTrue(submitRes.isOk, "Node A должна принять валидную транзакцию");
    Assertions.assertEquals(1, nodeA.blockchain.getMempoolTransactions().size());

    // Ожидаем gossip-распространения на Node B
    waitForCondition(() -> nodeB.blockchain.getMempoolTransactions().size() == 1, 3000, "Node B gossip sync");

    Transaction received = nodeB.blockchain.getMempoolTransactions().get(0);
    Assertions.assertEquals(tx.hash, received.hash);
    Assertions.assertEquals(1000L, received.amount);

    long expectedBal = 1_000_000_000_000L - (1000L + 50L);
    Assertions.assertEquals(expectedBal, nodeB.blockchain.calculateUnconfirmedBalance(sharedGenesisAddr));
  }

  // Проверяет распространение смайненного блока по сети и своевременную очистку мемпула.
  @Test
  public void testMinedBlockPropagationAndMempoolCleanup() throws Exception {
    TestNode nodeA = createAndStartNode("mining_a");
    TestNode nodeB = createAndStartNode("mining_b");

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 1, 3000);

    KeyPair recipient = CryptoUtil.generateKeyPair();
    String recipientAddr = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());
    Transaction tx = createSignedTransaction(sharedGenesisKey, recipientAddr, 2500L, 50L, 0L, "Block payout");

    nodeA.blockchain.submitTransaction(tx, true);
    waitForCondition(() -> nodeB.blockchain.getMempoolTransactions().size() == 1, 3000, "Node B mempool sync");

    Block minedBlock = nodeA.blockchain.mineBlock();
    Assertions.assertNotNull(minedBlock, "Node A должна успешно смайнить блок");
    Assertions.assertEquals(1, minedBlock.height);

    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node B sync block 1");

    Block nodeBLatest = nodeB.blockchain.getLatestBlockHeader();
    Assertions.assertEquals(minedBlock.hash, nodeBLatest.hash);
    Assertions.assertEquals(minedBlock.merkleRoot, nodeBLatest.merkleRoot);

    Assertions.assertTrue(nodeA.blockchain.getMempoolTransactions().isEmpty());
    Assertions.assertTrue(nodeB.blockchain.getMempoolTransactions().isEmpty());

    Account recipientAcc = nodeB.accountRepo.findByAddress(recipientAddr).orElse(null);
    Assertions.assertNotNull(recipientAcc);
    Assertions.assertEquals(2500L, recipientAcc.balance);
  }

  // Проверяет быструю синхронизацию (Fast-Sync IBD) новой ноды, подключающейся к уже выросшей цепи.
  @Test
  public void testFastSyncCatchUpNewNodeJoinsAdvancedNetwork() throws Exception {
    TestNode nodeA = createAndStartNode("fastsync_a");

    for (int i = 0; i < 3; i++) {
      Block b = nodeA.blockchain.mineBlock();
      Assertions.assertNotNull(b);
    }
    Assertions.assertEquals(3L, nodeA.blockchain.getLatestBlockHeader().height);

    TestNode nodeB = createAndStartNode("fastsync_b");
    Assertions.assertEquals(0L, nodeB.blockchain.getLatestBlockHeader().height);

    nodeB.p2p.connectToPeer("127.0.0.1", nodeA.port);
    waitForPeers(nodeB.p2p, 1, 3000);

    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 3L, 4000, "Node B catchup 3 blocks");

    Assertions.assertEquals(nodeA.blockchain.getLatestBlockHeader().hash, nodeB.blockchain.getLatestBlockHeader().hash);
    Assertions.assertTrue(nodeB.p2p.isSynced());
  }

  // Проверяет корректность реорганизации цепи (Chain Reorg) при сетевом разделении по правилу наибольшей работы.
  @Test
  public void testChainReorganizationBetweenTwoNodesLongestChainRule() throws Exception {
    TestNode nodeA = createAndStartNode("reorg_a");
    TestNode nodeB = createAndStartNode("reorg_b");

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    Block block1 = nodeA.blockchain.mineBlock();
    Assertions.assertNotNull(block1);
    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node B sync block 1");

    // Симулируем сетевое разделение (Network Partition)
    nodeA.p2p.stop();
    nodeB.p2p.stop();

    // Node A майнит 1 блок (#2A)
    Block block2A = nodeA.blockchain.mineBlock();
    Assertions.assertEquals(2L, nodeA.blockchain.getLatestBlockHeader().height);

    // Node B майнит 2 блока (#2B и #3B) — ветка длиннее
    Block block2B = nodeB.blockchain.mineBlock();
    Block block3B = nodeB.blockchain.mineBlock();
    Assertions.assertEquals(3L, nodeB.blockchain.getLatestBlockHeader().height);
    Assertions.assertNotEquals(block2A.hash, block2B.hash);

    // Восстанавливаем сеть
    nodeA.p2p.start();
    nodeB.p2p.start();
    Thread.sleep(100);

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);

    waitForCondition(() -> nodeA.blockchain.getLatestBlockHeader().height == 3L, 5000, "Node A reorg to height 3");

    Assertions.assertEquals(block3B.hash, nodeA.blockchain.getLatestBlockHeader().hash);
    Assertions.assertEquals(block3B.hash, nodeB.blockchain.getLatestBlockHeader().hash);
  }

  // Проверяет отклонение византийского блока с невалидным PoW и предотвращение его ретрансляции.
  @Test
  public void testMaliciousBlockRejectedAndNotPropagated() throws Exception {
    TestNode nodeHonest1 = createAndStartNode("honest_1");
    TestNode nodeHonest2 = createAndStartNode("honest_2");

    nodeHonest1.p2p.connectToPeer("127.0.0.1", nodeHonest2.port);
    waitForPeers(nodeHonest1.p2p, 1, 3000);
    waitForPeers(nodeHonest2.p2p, 1, 3000);

    Block latest = nodeHonest1.blockchain.getLatestBlockHeader();
    Block fakeBlock = new Block(latest.height + 1, nodeHonest1.config.chainId, latest.hash, nodeHonest1.wallet.address, Collections.emptyList(), 2);
    fakeBlock.hash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    boolean isApplied = nodeHonest1.blockchain.applyIncomingBlock(fakeBlock);
    Assertions.assertFalse(isApplied, "Честная нода обязана отклонить блок с невалидным PoW");

    Thread.sleep(100);
    Assertions.assertEquals(0L, nodeHonest1.blockchain.getLatestBlockHeader().height);
    Assertions.assertEquals(0L, nodeHonest2.blockchain.getLatestBlockHeader().height);
  }

  // Проверяет сквозную gossip-ретрансляцию в линейной топологии трех узлов: A <-> B <-> C.
  @Test
  public void testThreeNodeLinearRelayGossipMesh() throws Exception {
    TestNode nodeA = createAndStartNode("mesh_a");
    TestNode nodeB = createAndStartNode("mesh_b");
    TestNode nodeC = createAndStartNode("mesh_c");

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    nodeB.p2p.connectToPeer("127.0.0.1", nodeC.port);

    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 2, 3000);
    waitForPeers(nodeC.p2p, 1, 3000);

    KeyPair recipient = CryptoUtil.generateKeyPair();
    String recipientAddr = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());
    Transaction tx = createSignedTransaction(sharedGenesisKey, recipientAddr, 300L, 50L, 0L, "Relay through B");

    nodeA.blockchain.submitTransaction(tx, true);

    waitForCondition(() -> nodeB.blockchain.getMempoolTransactions().size() == 1, 3000, "Node B relay tx");
    waitForCondition(() -> nodeC.blockchain.getMempoolTransactions().size() == 1, 3000, "Node C relay tx");

    Assertions.assertEquals(tx.hash, nodeC.blockchain.getMempoolTransactions().get(0).hash);

    Block mined = nodeA.blockchain.mineBlock();
    Assertions.assertNotNull(mined);

    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node B relay block");
    waitForCondition(() -> nodeC.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node C relay block");

    Assertions.assertEquals(mined.hash, nodeC.blockchain.getLatestBlockHeader().hash);
  }

  // Проверяет автоматическое обнаружение пиров (Peer Discovery) через gossip-обмен GET_PEERS / PEERS_LIST.
  @Test
  public void testPeerDiscoveryViaGossip() throws Exception {
    TestNode nodeA = createAndStartNode("disco_a");
    TestNode nodeB = createAndStartNode("disco_b");
    TestNode nodeC = createAndStartNode("disco_c");

    nodeC.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeC.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 1, 3000);

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 2, 3000);

    waitForCondition(() -> {
      List<PeerInfo> known = nodeA.peerRepo.findAll();
      for (PeerInfo p : known) {
        if (p.port() == nodeC.port) return true;
      }
      return false;
    }, 4000, "Node A discovery of Node C");

    boolean isFound = nodeA.peerRepo.findAll().stream().anyMatch(p -> p.port() == nodeC.port);
    Assertions.assertTrue(isFound, "Node A должна сохранить порт Node C в таблице peers");
  }

  // Проверяет устойчивость к обрыву соединения и автоматическое догоняние цепи при переподключении.
  @Test
  public void testNodeDisconnectionAndReconnectResilience() throws Exception {
    TestNode nodeA = createAndStartNode("disc_res_a");
    TestNode nodeB = createAndStartNode("disc_res_b");

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);
    waitForPeers(nodeB.p2p, 1, 3000);

    nodeB.p2p.stop();
    waitForCondition(() -> nodeA.p2p.getConnectedPeersCount() == 0, 3000, "Node A detect drop");
    Assertions.assertEquals(0, nodeA.p2p.getConnectedPeersCount());

    Block b1 = nodeA.blockchain.mineBlock();
    Assertions.assertNotNull(b1);

    nodeB.p2p.start();
    Thread.sleep(100);

    nodeA.p2p.connectToPeer("127.0.0.1", nodeB.port);
    waitForPeers(nodeA.p2p, 1, 3000);

    waitForCondition(() -> nodeB.blockchain.getLatestBlockHeader().height == 1L, 3000, "Node B catchup b1");
    Assertions.assertEquals(b1.hash, nodeB.blockchain.getLatestBlockHeader().hash);
  }

  // Проверяет отклонение рукопожатия от узлов с чужим идентификатором сети Chain ID.
  @Test
  public void testWrongChainIdHandshakeRejected() throws Exception {
    TestNode nodeMain = createAndStartNode("main_chain");

    int roguePort = findAvailablePort();
    TestNode nodeRogue = new TestNode("rogue_node", roguePort, sharedGenesisAddr);
    nodeRogue.config.chainId = "foxchain-testnet-wrong";
    activeNodes.add(nodeRogue);
    nodeRogue.start();

    nodeMain.p2p.connectToPeer("127.0.0.1", nodeRogue.port);
    Thread.sleep(200);

    Assertions.assertEquals(0, nodeMain.p2p.getConnectedPeersCount());
    Assertions.assertEquals(0, nodeRogue.p2p.getConnectedPeersCount());
  }

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
      throw new RuntimeException("Не удалось выделить свободный порт", e);
    }
  }

  private static void waitForPeers(P2pClientManager p2p, int count, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (p2p.getConnectedPeersCount() >= count) return;
      Thread.sleep(25);
    }
    Assertions.fail("Таймаут ожидания " + count + " подключений");
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
    long ts = System.currentTimeMillis();
    String from = CryptoUtil.getAddressFromPublicKey(senderKey.getPublic());
    String pubKey = CryptoUtil.encodePublicKey(senderKey.getPublic());
    String canonical = Transaction.buildCanonicalData("foxchain-main-1", from, to, amount, fee, nonce, ts, comment);
    String sig = CryptoUtil.sign(senderKey.getPrivate(), canonical);
    return new Transaction("foxchain-main-1", from, to, amount, fee, nonce, ts, pubKey, sig, comment);
  }
}
