package so.fox.nodechain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import so.fox.nodechain.api.ApiController.SendTxRequest;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.ConsensusUtil;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Account;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;

/**
 * Интеграционные тесты блокчейна: REST API, майнинг, reorg, лимиты mempool и PoW.
 */
@SpringBootTest(properties = {
  "server.port=0",
  "node.p2p.port=9999",
  "node.db.path=test_sqlite.db",
  "blockchain.auto-mine=false"
})
@AutoConfigureMockMvc
public class BlockchainIntegrationTest {

  static {
    cleanDbFiles();
  }

  @BeforeAll
  @AfterAll
  public static void cleanup() {
    cleanDbFiles();
  }

  private static void cleanDbFiles() {
    new File("test_sqlite.db").delete();
    new File("test_sqlite.db-wal").delete();
    new File("test_sqlite.db-shm").delete();
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BlockchainService blockchainService;

  @Autowired
  private NodeWalletService nodeWalletService;

  @Autowired
  private so.fox.nodechain.config.NodeConfig nodeConfig;

  @Autowired
  private ObjectMapper objectMapper;

  /**
   * Проверяет начисление начальной эмиссии Genesis Allocation (1 000 000 000 000 атомов) на адрес ноды.
   */
  @Test
  public void testGenesisAllocationCreditedToNodeAddress() throws Exception {
    mockMvc.perform(get("/api/v1/addresses/" + nodeWalletService.address))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.address").value(nodeWalletService.address.toLowerCase()))
      .andExpect(jsonPath("$.nonce").isNumber());

    Account acc = blockchainService.getAccountState(nodeWalletService.address).value;
    Assertions.assertNotNull(acc);
    Assertions.assertTrue(acc.balance >= 1_000_000_000_000L,
      "Баланс адреса ноды должен содержать как минимум сумму Genesis Allocation (1 000 000 000 000 атомов)");
  }

  /**
   * Проверяет эндпоинт состояния сети.
   */
  @Test
  public void testNetworkState() throws Exception {
    mockMvc.perform(get("/api/v1/network"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.chainId").value("foxchain-main-1"))
      .andExpect(jsonPath("$.isSynced").value(true))
      .andExpect(jsonPath("$.version").value("1.0.0"));
  }

  /**
   * Проверяет оценку комиссии для разных приоритетов.
   */
  @Test
  public void testFeeEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/transactions/fee?priority=high"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(100))
      .andExpect(jsonPath("$.priority").value("high"));

    mockMvc.perform(get("/api/v1/transactions/fee"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.fee").value(50))
      .andExpect(jsonPath("$.priority").value("normal"));
  }

  /**
   * Проверяет получение последнего блока и блока #0.
   */
  @Test
  public void testBlocksEndpoints() throws Exception {
    mockMvc.perform(get("/api/v1/blocks/latest"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.hash").isNotEmpty());

    mockMvc.perform(get("/api/v1/blocks/0"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.height").value(0))
      .andExpect(jsonPath("$.previousHash").value("0"));
  }

  /**
   * Проверяет ответы при запросе несуществующего или невалидного адреса.
   */
  @Test
  public void testAddressNotFoundAndInvalidAddress() throws Exception {
    mockMvc.perform(get("/api/v1/addresses/not-a-valid-address"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value(400))
      .andExpect(jsonPath("$.message").value("Некорректный формат адреса"));

    KeyPair kp = CryptoUtil.generateRsaKeyPair();
    String unused = CryptoUtil.getAddressFromPublicKey(kp.getPublic());

    mockMvc.perform(get("/api/v1/addresses/" + unused))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value(404))
      .andExpect(jsonPath("$.message").value("Адрес еще не совершал транзакций и не имеет баланса"));
  }

  /**
   * Проверяет отправку транзакции, майнинг и обновление балансов.
   */
  @Test
  public void testTransactionSendAndMine() throws Exception {
    KeyPair senderKp = CryptoUtil.generateRsaKeyPair();
    String sender = CryptoUtil.getAddressFromPublicKey(senderKp.getPublic());

    KeyPair recipientKp = CryptoUtil.generateRsaKeyPair();
    String recipient = CryptoUtil.getAddressFromPublicKey(recipientKp.getPublic());

    Account senderAcc = blockchainService.getAccount(sender);
    senderAcc.balance = 1000L;
    senderAcc.nonce = 0L;
    blockchainService.saveAccount(senderAcc);

    long amount = 300L;
    long fee = 50L;
    long nonce = 0L;
    String signData = sender + "|" + recipient + "|" + amount + "|" + fee + "|" + nonce;
    String sig = CryptoUtil.sign(senderKp.getPrivate(), signData);
    String pubKeyB64 = CryptoUtil.encodePublicKey(senderKp.getPublic());

    SendTxRequest req = new SendTxRequest(
      sender,
      recipient,
      amount,
      fee,
      nonce,
      pubKeyB64,
      sig
    );

    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(req)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("pending"))
      .andExpect(jsonPath("$.addedAt").isNumber())
      .andExpect(jsonPath("$.nonce").value(0))
      .andExpect(jsonPath("$.fee").value(50));

    blockchainService.mineBlock();

    mockMvc.perform(get("/api/v1/addresses/" + sender))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.txCount").value(1));

    Account recipientAcc = blockchainService.getAccountState(recipient).value;
    Assertions.assertEquals(300L, recipientAcc.balance);

    Account updatedSender = blockchainService.getAccountState(sender).value;
    Assertions.assertEquals(650L, updatedSender.balance);
    Assertions.assertEquals(1L, updatedSender.nonce);
  }

  /**
   * Проверяет отклонение запроса отправки транзакции с пустым телом.
   */
  @Test
  public void testSendTransactionEmptyBody() throws Exception {
    mockMvc.perform(post("/api/v1/transactions/send")
        .contentType(MediaType.APPLICATION_JSON))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value(409))
      .andExpect(jsonPath("$.message").value("Транзакция отклонена"));
  }

  /**
   * Проверяет просмотр mempool через REST эндпоинт.
   */
  @Test
  public void testMempoolEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/transactions/mempool"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.count").isNumber())
      .andExpect(jsonPath("$.transactions").isArray());
  }

  /**
   * Проверяет отклонение блоков с невалидным timestamp (из будущего или прошлого).
   */
  @Test
  public void testTimestampValidationRejection() {
    Block latest = blockchainService.getLatestBlockHeader();

    Block futureBlock = new Block(
      latest.height + 1,
      latest.hash,
      "miner-future",
      Collections.emptyList()
    );
    futureBlock.timestamp = System.currentTimeMillis() + 800_000;
    futureBlock.mineBlock(2);
    boolean isFutureApplied = blockchainService.applyIncomingBlock(futureBlock);
    Assertions.assertFalse(isFutureApplied, "Block from distant future must be rejected");

    Block pastBlock = new Block(
      latest.height + 1,
      latest.hash,
      "miner-past",
      Collections.emptyList()
    );
    pastBlock.timestamp = latest.timestamp - 1000;
    pastBlock.mineBlock(2);
    boolean isPastApplied = blockchainService.applyIncomingBlock(pastBlock);
    Assertions.assertFalse(isPastApplied, "Block older than previous block must be rejected");
  }

  /**
   * Проверяет реорганизацию цепи при появлении более длинного форка.
   */
  @SuppressWarnings("null")
  @Test
  public void testChainReorganization() {
    KeyPair minerKp = CryptoUtil.generateRsaKeyPair();
    String altMiner = CryptoUtil.getAddressFromPublicKey(minerKp.getPublic());

    Block genesis = blockchainService.getBlockByIdentifier("0").value;
    long base = System.currentTimeMillis();

    long targetHeight = blockchainService.getLatestBlockHeader().height + 1;
    List<Block> forkChain = new ArrayList<>();
    String prevHash = genesis.hash;
    Block lastForkBlock = null;

    for (long h = 1; h <= targetHeight; h++) {
      Block fb = new Block(h, prevHash, altMiner, Collections.emptyList());
      fb.timestamp = base + (h * 100);
      fb.mineBlock(2);
      forkChain.add(fb);
      prevHash = fb.hash;
      lastForkBlock = fb;
    }

    boolean isReorgOk = blockchainService.reorganizeChain(forkChain);
    Assertions.assertTrue(isReorgOk, "Reorganization to longer fork must succeed");

    Block newLatest = blockchainService.getLatestBlockHeader();
    Assertions.assertEquals(targetHeight, newLatest.height);
    Assertions.assertEquals(lastForkBlock.hash, newLatest.hash);

    Account altAcc = blockchainService.getAccountState(altMiner).value;
    Assertions.assertNotNull(altAcc);
    Assertions.assertTrue(altAcc.balance > 0, "Miner on accepted fork must receive reward");
  }

  /**
   * Проверяет ограничение размера мемпула и вытеснение транзакций с меньшей комиссией.
   */
  @Test
  public void testMempoolLimitAndEviction() {
    int originalLimit = nodeConfig.maxMempoolSize;
    try {
      nodeConfig.maxMempoolSize = 1;

      KeyPair p1 = CryptoUtil.generateRsaKeyPair();
      String addr1 = CryptoUtil.getAddressFromPublicKey(p1.getPublic());
      Account a1 = blockchainService.getAccount(addr1);
      a1.balance = 10_000L;
      a1.nonce = 0L;
      blockchainService.saveAccount(a1);

      KeyPair p2 = CryptoUtil.generateRsaKeyPair();
      String addr2 = CryptoUtil.getAddressFromPublicKey(p2.getPublic());
      Account a2 = blockchainService.getAccount(addr2);
      a2.balance = 10_000L;
      a2.nonce = 0L;
      blockchainService.saveAccount(a2);

      String d1 = addr1 + "|" + addr2 + "|100|20|0";
      String sig1 = CryptoUtil.sign(p1.getPrivate(), d1);
      Transaction tx1 = new Transaction(
        addr1, addr2, 100L, 20L, 0L, CryptoUtil.encodePublicKey(p1.getPublic()), sig1, ""
      );
      var res1 = blockchainService.submitTransaction(tx1, false);
      Assertions.assertFalse(res1.isError());

      String d2 = addr2 + "|" + addr1 + "|100|10|0";
      String sig2 = CryptoUtil.sign(p2.getPrivate(), d2);
      Transaction tx2 = new Transaction(
        addr2, addr1, 100L, 10L, 0L, CryptoUtil.encodePublicKey(p2.getPublic()), sig2, ""
      );
      var res2 = blockchainService.submitTransaction(tx2, false);
      Assertions.assertTrue(res2.isError());
      Assertions.assertEquals(429, res2.status);
      Assertions.assertEquals("Mempool переполнен", res2.error);

      String d3 = addr2 + "|" + addr1 + "|100|50|0";
      String sig3 = CryptoUtil.sign(p2.getPrivate(), d3);
      Transaction tx3 = new Transaction(
        addr2, addr1, 100L, 50L, 0L, CryptoUtil.encodePublicKey(p2.getPublic()), sig3, ""
      );
      var res3 = blockchainService.submitTransaction(tx3, false);
      Assertions.assertFalse(res3.isError());

      Transaction dbTx1 = blockchainService.getTransactionByHash(tx1.hash).value;
      Assertions.assertEquals("failed", dbTx1.status);
    } finally {
      nodeConfig.maxMempoolSize = originalLimit;
    }
  }

  /**
   * Проверяет защиту от DoS: отправитель без баланса не может вытеснить транзакцию из мемпула.
   */
  @Test
  public void testDosExploitSenderWithoutBalanceCannotEvictMempool() {
    int originalLimit = nodeConfig.maxMempoolSize;
    try {
      nodeConfig.maxMempoolSize = 1;

      KeyPair legitKp = CryptoUtil.generateRsaKeyPair();
      String legitAddr = CryptoUtil.getAddressFromPublicKey(legitKp.getPublic());
      Account legitAcc = blockchainService.getAccount(legitAddr);
      legitAcc.balance = 5_000L;
      legitAcc.nonce = 0L;
      blockchainService.saveAccount(legitAcc);

      KeyPair dummyKp = CryptoUtil.generateRsaKeyPair();
      String dummyAddr = CryptoUtil.getAddressFromPublicKey(dummyKp.getPublic());

      String legitData = legitAddr + "|" + dummyAddr + "|100|10|0";
      String legitSig = CryptoUtil.sign(legitKp.getPrivate(), legitData);
      Transaction legitTx = new Transaction(
        legitAddr, dummyAddr, 100L, 10L, 0L, CryptoUtil.encodePublicKey(legitKp.getPublic()), legitSig, ""
      );
      var legitRes = blockchainService.submitTransaction(legitTx, false);
      Assertions.assertFalse(legitRes.isError());

      KeyPair attackerKp = CryptoUtil.generateRsaKeyPair();
      String attackerAddr = CryptoUtil.getAddressFromPublicKey(attackerKp.getPublic());
      String attackData = attackerAddr + "|" + dummyAddr + "|100|999999|0";
      String attackSig = CryptoUtil.sign(attackerKp.getPrivate(), attackData);
      Transaction attackTx = new Transaction(
        attackerAddr, dummyAddr, 100L, 999999L, 0L, CryptoUtil.encodePublicKey(attackerKp.getPublic()), attackSig, ""
      );
      var attackRes = blockchainService.submitTransaction(attackTx, false);
      Assertions.assertTrue(attackRes.isError());
      Assertions.assertEquals(409, attackRes.status);
      Assertions.assertEquals("Недостаточно средств", attackRes.error);

      Transaction checkLegit = blockchainService.getTransactionByHash(legitTx.hash).value;
      Assertions.assertEquals("pending", checkLegit.status);
      Assertions.assertTrue(blockchainService.getMempoolTransactions().stream()
        .anyMatch(t -> t.hash.equals(legitTx.hash)));
    } finally {
      nodeConfig.maxMempoolSize = originalLimit;
    }
  }

  /**
   * Проверяет детерминированную целостность корня Меркла при сохранении и восстановлении из БД.
   */
  @Test
  public void testMerkleRootDeterministicOrderIntegrity() {
    KeyPair p1 = CryptoUtil.generateRsaKeyPair();
    String addr1 = CryptoUtil.getAddressFromPublicKey(p1.getPublic());
    Account a1 = blockchainService.getAccount(addr1);
    a1.balance = 100_000L;
    a1.nonce = 0L;
    blockchainService.saveAccount(a1);

    KeyPair p2 = CryptoUtil.generateRsaKeyPair();
    String addr2 = CryptoUtil.getAddressFromPublicKey(p2.getPublic());

    String d1 = addr1 + "|" + addr2 + "|10|10|0";
    String sig1 = CryptoUtil.sign(p1.getPrivate(), d1);
    Transaction tx1 = new Transaction(addr1, addr2, 10L, 10L, 0L, CryptoUtil.encodePublicKey(p1.getPublic()), sig1, "");
    tx1.timestamp = System.currentTimeMillis() - 5000;
    blockchainService.submitTransaction(tx1, false);

    String d2 = addr1 + "|" + addr2 + "|20|100|1";
    String sig2 = CryptoUtil.sign(p1.getPrivate(), d2);
    Transaction tx2 = new Transaction(addr1, addr2, 20L, 100L, 1L, CryptoUtil.encodePublicKey(p1.getPublic()), sig2, "");
    tx2.timestamp = System.currentTimeMillis();
    blockchainService.submitTransaction(tx2, false);

    Block mined = blockchainService.mineBlock();
    Assertions.assertNotNull(mined);
    Assertions.assertTrue(mined.transactions.size() >= 2);
    Assertions.assertTrue(mined.transactions.stream().anyMatch(t -> t.hash.equals(tx1.hash)));
    Assertions.assertTrue(mined.transactions.stream().anyMatch(t -> t.hash.equals(tx2.hash)));

    Block reloaded = blockchainService.getBlockByIdentifier(String.valueOf(mined.height)).value;
    Assertions.assertEquals(mined.merkleRoot, reloaded.calculateMerkleRoot());
  }

  /**
   * Проверяет, что лимит пагинации безопасно ограничен сверху.
   */
  @Test
  public void testPaginationLimitCapped() throws Exception {
    KeyPair kp = CryptoUtil.generateRsaKeyPair();
    String addr = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    mockMvc.perform(get("/api/v1/addresses/" + addr + "/transactions?limit=999999"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.total").value(0))
      .andExpect(jsonPath("$.txs").isArray());
  }

  /**
   * Проверяет, что неподтвержденные входящие транзакции нельзя тратить до включения в блок.
   */
  @Test
  public void testUnconfirmedIncomingBalanceCannotBeSpent() {
    KeyPair bobKp = CryptoUtil.generateRsaKeyPair();
    String bob = CryptoUtil.getAddressFromPublicKey(bobKp.getPublic());
    Account bobAcc = blockchainService.getAccount(bob);
    bobAcc.balance = 2000L;
    bobAcc.nonce = 0L;
    blockchainService.saveAccount(bobAcc);

    KeyPair aliceKp = CryptoUtil.generateRsaKeyPair();
    String alice = CryptoUtil.getAddressFromPublicKey(aliceKp.getPublic());

    KeyPair charlieKp = CryptoUtil.generateRsaKeyPair();
    String charlie = CryptoUtil.getAddressFromPublicKey(charlieKp.getPublic());

    // Bob -> Alice (500), pending in mempool
    String bobData = bob + "|" + alice + "|500|50|0";
    String bobSig = CryptoUtil.sign(bobKp.getPrivate(), bobData);
    Transaction bobTx = new Transaction(
      bob, alice, 500L, 50L, 0L, CryptoUtil.encodePublicKey(bobKp.getPublic()), bobSig, ""
    );
    var bobRes = blockchainService.submitTransaction(bobTx, false);
    Assertions.assertFalse(bobRes.isError());

    // Alice tries to spend 200 to Charlie before Bob's tx is confirmed -> must fail!
    String aliceData = alice + "|" + charlie + "|200|20|0";
    String aliceSig = CryptoUtil.sign(aliceKp.getPrivate(), aliceData);
    Transaction aliceTx = new Transaction(
      alice, charlie, 200L, 20L, 0L, CryptoUtil.encodePublicKey(aliceKp.getPublic()), aliceSig, ""
    );
    var aliceRes = blockchainService.submitTransaction(aliceTx, false);
    Assertions.assertTrue(aliceRes.isError());
    Assertions.assertEquals(409, aliceRes.status);
    Assertions.assertEquals("Недостаточно средств", aliceRes.error);
  }

  /**
   * Проверяет приоритет более высокой комиссии при майнинге между разными отправителями.
   */
  @Test
  public void testMinerFeePrioritizationAcrossAccounts() {
    KeyPair lowKp = CryptoUtil.generateRsaKeyPair();
    String lowAddr = CryptoUtil.getAddressFromPublicKey(lowKp.getPublic());
    Account lowAcc = blockchainService.getAccount(lowAddr);
    lowAcc.balance = 10_000L;
    lowAcc.nonce = 0L;
    blockchainService.saveAccount(lowAcc);

    KeyPair highKp = CryptoUtil.generateRsaKeyPair();
    String highAddr = CryptoUtil.getAddressFromPublicKey(highKp.getPublic());
    Account highAcc = blockchainService.getAccount(highAddr);
    highAcc.balance = 10_000L;
    highAcc.nonce = 0L;
    blockchainService.saveAccount(highAcc);

    KeyPair destKp = CryptoUtil.generateRsaKeyPair();
    String destAddr = CryptoUtil.getAddressFromPublicKey(destKp.getPublic());

    // Low fee transaction submitted earlier
    String lowData = lowAddr + "|" + destAddr + "|100|10|0";
    String lowSig = CryptoUtil.sign(lowKp.getPrivate(), lowData);
    Transaction lowTx = new Transaction(
      lowAddr, destAddr, 100L, 10L, 0L, CryptoUtil.encodePublicKey(lowKp.getPublic()), lowSig, ""
    );
    lowTx.timestamp = System.currentTimeMillis() - 2000;
    blockchainService.submitTransaction(lowTx, false);

    // High fee transaction submitted later
    String highData = highAddr + "|" + destAddr + "|100|500|0";
    String highSig = CryptoUtil.sign(highKp.getPrivate(), highData);
    Transaction highTx = new Transaction(
      highAddr, destAddr, 100L, 500L, 0L, CryptoUtil.encodePublicKey(highKp.getPublic()), highSig, ""
    );
    highTx.timestamp = System.currentTimeMillis();
    blockchainService.submitTransaction(highTx, false);

    Block mined = blockchainService.mineBlock();
    Assertions.assertNotNull(mined);
    Assertions.assertTrue(mined.transactions.size() >= 2);
    // Highest fee transaction must be ordered first in the mined block
    Assertions.assertEquals(highTx.hash, mined.transactions.get(0).hash);
    Assertions.assertEquals(lowTx.hash, mined.transactions.get(1).hash);
  }

  /**
   * Проверяет успешную отправку нескольких транзакций подряд с последовательными nonce (пайплайнинг).
   */
  @Test
  public void testPipelinedConsecutiveNoncesInMempool() {
    KeyPair kp = CryptoUtil.generateRsaKeyPair();
    String sender = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    Account acc = blockchainService.getAccount(sender);
    acc.balance = 50_000L;
    acc.nonce = 0L;
    blockchainService.saveAccount(acc);

    KeyPair destKp = CryptoUtil.generateRsaKeyPair();
    String dest = CryptoUtil.getAddressFromPublicKey(destKp.getPublic());

    String d0 = sender + "|" + dest + "|100|10|0";
    String sig0 = CryptoUtil.sign(kp.getPrivate(), d0);
    Transaction tx0 = new Transaction(sender, dest, 100L, 10L, 0L, CryptoUtil.encodePublicKey(kp.getPublic()), sig0, "");
    var res0 = blockchainService.submitTransaction(tx0, false);
    Assertions.assertFalse(res0.isError(), "Nonce 0 must be accepted into mempool");

    String d1 = sender + "|" + dest + "|200|15|1";
    String sig1 = CryptoUtil.sign(kp.getPrivate(), d1);
    Transaction tx1 = new Transaction(sender, dest, 200L, 15L, 1L, CryptoUtil.encodePublicKey(kp.getPublic()), sig1, "");
    var res1 = blockchainService.submitTransaction(tx1, false);
    Assertions.assertFalse(res1.isError(), "Sequential nonce 1 must be accepted into mempool");

    Block mined = blockchainService.mineBlock();
    Assertions.assertNotNull(mined);

    Account updated = blockchainService.getAccountState(sender).value;
    Assertions.assertEquals(2L, updated.nonce);
    Assertions.assertEquals(50_000L - 110L - 215L, updated.balance);
  }

  /**
   * Проверяет защиту от Transaction Malleability: подмена комментария делает подпись невалидной.
   */
  @Test
  public void testTransactionMalleabilityRejection() {
    KeyPair kp = CryptoUtil.generateRsaKeyPair();
    String sender = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    KeyPair destKp = CryptoUtil.generateRsaKeyPair();
    String dest = CryptoUtil.getAddressFromPublicKey(destKp.getPublic());

    // Честный отправитель подписал транзакцию без комментария
    String data = sender + "|" + dest + "|100|10|0";
    String sig = CryptoUtil.sign(kp.getPrivate(), data);

    Transaction tx = new Transaction(sender, dest, 100L, 10L, 0L, CryptoUtil.encodePublicKey(kp.getPublic()), sig, "");
    Assertions.assertTrue(tx.isSignatureValid());

    // Злоумышленник внедряет комментарий в перехваченную транзакцию
    tx.comment = "attacker_injected_comment";
    Assertions.assertFalse(tx.isSignatureValid(), "Tampered comment must invalidate transaction signature");
  }

  /**
   * Проверяет отклонение транзакции со слишком длинным комментарием (>256) и null хешем.
   */
  @Test
  public void testLongCommentAndNullHashRejection() {
    KeyPair kp = CryptoUtil.generateRsaKeyPair();
    String sender = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    KeyPair destKp = CryptoUtil.generateRsaKeyPair();
    String dest = CryptoUtil.getAddressFromPublicKey(destKp.getPublic());

    String longComment = "a".repeat(257);
    String data = sender + "|" + dest + "|100|10|0|" + longComment;
    String sig = CryptoUtil.sign(kp.getPrivate(), data);

    Transaction tx = new Transaction(sender, dest, 100L, 10L, 0L, CryptoUtil.encodePublicKey(kp.getPublic()), sig, longComment);
    var res = blockchainService.submitTransaction(tx, false);
    Assertions.assertTrue(res.isError());
    Assertions.assertEquals("Транзакция отклонена: слишком длинный комментарий", res.error);

    tx.comment = "";
    tx.hash = null;
    var hashRes = ConsensusUtil.validateTransactionBasics(tx);
    Assertions.assertTrue(hashRes.isError());
    Assertions.assertEquals("Транзакция отклонена: неверный хеш", hashRes.error);
  }

  /**
   * Проверяет регистронезависимый поиск транзакций адреса.
   */
  @Test
  public void testUppercaseAddressTransactionsQuery() {
    KeyPair kp = CryptoUtil.generateRsaKeyPair();
    String addr = CryptoUtil.getAddressFromPublicKey(kp.getPublic());

    var res = blockchainService.getAddressTransactions(addr.toUpperCase(), 10, 0);
    Assertions.assertFalse(res.isError());

    var countRes = blockchainService.countAddressTransactions(addr.toUpperCase());
    Assertions.assertFalse(countRes.isError());
  }

  /**
   * Проверяет отклонение блоков с поддельным txCount или невалидным minerAddress.
   */
  @Test
  public void testBlockInvalidTxCountOrMinerAddressRejection() {
    Block latest = blockchainService.getLatestBlockHeader();
    Block b = new Block(new Block.Draft(latest.height + 1, latest.hash, "invalid_miner_addr"), Collections.emptyList());
    b.mineBlock(2);
    Assertions.assertFalse(blockchainService.applyIncomingBlock(b), "Block with invalid minerAddress must be rejected");

    Block b2 = new Block(new Block.Draft(latest.height + 1, latest.hash, latest.minerAddress), Collections.emptyList());
    b2.txCount = 999;
    b2.mineBlock(2);
    Assertions.assertFalse(blockchainService.applyIncomingBlock(b2), "Block with mismatched txCount must be rejected");
  }
}
