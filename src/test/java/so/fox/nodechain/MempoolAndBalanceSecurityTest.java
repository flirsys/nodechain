package so.fox.nodechain;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.security.KeyPair;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.repository.AccountRepository;
import so.fox.nodechain.repository.BlockRepository;
import so.fox.nodechain.repository.TransactionRepository;

// Тесты безопасности пула памяти (Mempool): балансы, очереди nonce, защита от двойной траты и DoS.
public class MempoolAndBalanceSecurityTest {

  private static final String TEST_DB = "test_mempool_sec.db";
  private HikariDataSource dataSource;
  private BlockchainService service;
  private NodeConfig config;
  private KeyPair genesisKey;

  private static void cleanDb() {
    new File(TEST_DB).delete();
    new File(TEST_DB + "-wal").delete();
    new File(TEST_DB + "-shm").delete();
  }

  @BeforeEach
  public void setUp() {
    cleanDb();
    HikariConfig hCfg = new HikariConfig();
    hCfg.setDriverClassName("org.sqlite.JDBC");
    hCfg.setJdbcUrl("jdbc:sqlite:" + TEST_DB);
    hCfg.setMaximumPoolSize(1);
    dataSource = new HikariDataSource(hCfg);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    DatabaseConfig.initDatabase(jdbc);

    BlockRepository blockRepo = new BlockRepository(jdbc);
    TransactionRepository txRepo = new TransactionRepository(jdbc);
    AccountRepository accRepo = new AccountRepository(jdbc);

    config = new NodeConfig();
    config.chainId = "foxchain-main-1";
    config.difficulty = 1;
    config.minDifficulty = 1;
    config.maxDifficulty = 8;
    config.isAutoMine = false;
    config.maxMempoolSize = 5;
    config.genesisAllocation = 1_000_000_000_000L;
    genesisKey = CryptoUtil.generateKeyPair();
    config.genesisAddress = CryptoUtil.getAddressFromPublicKey(genesisKey.getPublic());

    PlatformTransactionManager txMgr = new DataSourceTransactionManager(dataSource);
    NodeWalletService wallet = new NodeWalletService();
    service = new BlockchainService(config, blockRepo, txRepo, accRepo, wallet, txMgr, null);
    service.init();
  }

  @AfterEach
  public void tearDown() {
    if (dataSource != null) dataSource.close();
    cleanDb();
  }

  private Transaction createTx(KeyPair senderKp, String to, long amount, long fee, long nonce) {
    long ts = System.currentTimeMillis();
    String from = CryptoUtil.getAddressFromPublicKey(senderKp.getPublic());
    String pub = CryptoUtil.encodePublicKey(senderKp.getPublic());
    String canonical = Transaction.buildCanonicalData(config.chainId, from, to, amount, fee, nonce, ts, "");
    String sig = CryptoUtil.sign(senderKp.getPrivate(), canonical);
    return new Transaction(config.chainId, from, to, amount, fee, nonce, ts, pub, sig, "");
  }

  // Проверяет синхронизацию nonce в mempool и расчет неподтвержденного баланса.
  @Test
  @DisplayName("Mempool: правильная цепочка nonce и уменьшение unconfirmedBalance")
  public void testSequentialNonceAndUnconfirmedBalance() {
    String recipient = CryptoUtil.getAddressFromPublicKey(CryptoUtil.generateKeyPair().getPublic());

    Transaction tx0 = createTx(genesisKey, recipient, 1000L, 50L, 0L);
    Result<Transaction> r0 = service.submitTransaction(tx0, false);
    Assertions.assertTrue(r0.isOk);

    BlockchainService.AccountDetails d0 = service.getAccountDetails(config.genesisAddress).value;
    Assertions.assertEquals(1L, d0.nextPendingNonce());
    Assertions.assertEquals(config.genesisAllocation - 1050L, d0.unconfirmedBalance());

    Transaction tx1 = createTx(genesisKey, recipient, 2000L, 50L, 1L);
    Result<Transaction> r1 = service.submitTransaction(tx1, false);
    Assertions.assertTrue(r1.isOk);

    BlockchainService.AccountDetails d1 = service.getAccountDetails(config.genesisAddress).value;
    Assertions.assertEquals(2L, d1.nextPendingNonce());
    Assertions.assertEquals(config.genesisAllocation - 3100L, d1.unconfirmedBalance());
  }

  // Проверяет защиту от двойной траты и отклонение повторного использования одного и того же nonce.
  @Test
  @DisplayName("Двойная трата: отклонение конфликтующего nonce в очереди mempool")
  public void testDoubleSpendingNonceConflictRejected() {
    String r1 = CryptoUtil.getAddressFromPublicKey(CryptoUtil.generateKeyPair().getPublic());
    String r2 = CryptoUtil.getAddressFromPublicKey(CryptoUtil.generateKeyPair().getPublic());

    Transaction txA = createTx(genesisKey, r1, 1000L, 50L, 0L);
    Assertions.assertTrue(service.submitTransaction(txA, false).isOk);

    Transaction txB = createTx(genesisKey, r2, 2000L, 100L, 0L);
    Result<Transaction> rB = service.submitTransaction(txB, false);
    Assertions.assertFalse(rB.isOk);
    Assertions.assertEquals(409, rB.status);
  }

  // Проверяет защиту от переполнения мемпула и вытеснение транзакций с наименьшей комиссией.
  @Test
  @DisplayName("DoS защита mempool: вытеснение транзакции с низкой комиссией при достижении maxMempoolSize")
  public void testMempoolEvictionOfLowestFeeTx() {
    String to = CryptoUtil.getAddressFromPublicKey(CryptoUtil.generateKeyPair().getPublic());

    // Заполняем лимит мемпула (maxMempoolSize = 5)
    for (int i = 0; i < 5; i++) {
      Transaction tx = createTx(genesisKey, to, 100L, 10L, i);
      Assertions.assertTrue(service.submitTransaction(tx, false).isOk);
    }
    Assertions.assertEquals(5, service.getMempoolTransactions().size());

    // Попытка отправить транзакцию с такой же комиссией отклоняется (требуется MIN_FEE_BUMP = +5)
    KeyPair sender2 = CryptoUtil.generateKeyPair();
    String sender2Addr = CryptoUtil.getAddressFromPublicKey(sender2.getPublic());
    service.saveAccount(new Account(sender2Addr, 100_000L, 0L));

    Transaction txLowFee = createTx(sender2, to, 100L, 10L, 0L);
    Result<Transaction> rLow = service.submitTransaction(txLowFee, false);
    Assertions.assertFalse(rLow.isOk);
    Assertions.assertEquals(429, rLow.status);

    // Транзакция с повышенной комиссией (+15 > MIN_FEE_BUMP) вытесняет последнюю транзакцию
    Transaction txHighFee = createTx(sender2, to, 100L, 25L, 0L);
    Result<Transaction> rHigh = service.submitTransaction(txHighFee, false);
    Assertions.assertTrue(rHigh.isOk);
    Assertions.assertEquals(5, service.getMempoolTransactions().size());
  }
}

