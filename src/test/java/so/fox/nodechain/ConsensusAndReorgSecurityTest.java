package so.fox.nodechain;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.security.KeyPair;
import java.util.ArrayList;
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
import so.fox.nodechain.core.ConsensusUtil;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.repository.AccountRepository;
import so.fox.nodechain.repository.BlockRepository;
import so.fox.nodechain.repository.TransactionRepository;

// Тесты безопасности консенсуса, DAA-ретаргетинга, защиты от форков и 51% атак.
public class ConsensusAndReorgSecurityTest {

  private static final String TEST_DB = "test_consensus_sec.db";
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
    config.difficultyAdjustmentInterval = 4;
    config.targetBlockTimeSeconds = 10;
    config.isAutoMine = false;
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

  // Проверяет, что манипуляция временем майнером (замороженное MTP) не повышает сложность (difficulty ratchet).
  @Test
  @DisplayName("Консенсус: замороженное медианное время (MTP-11) не накручивает сложность вверх")
  public void testFrozenTimestampsDoNotRatchetDifficulty() {
    Block prev = service.getLatestBlockHeader();
    long t = prev.timestamp;

    for (int i = 1; i <= 10; i++) {
      int diff = service.getNextBlockDifficulty(prev);
      Block b = new Block(prev.height + 1, config.chainId, prev.hash, config.genesisAddress, List.of(), diff);
      t += 1L; // Шаг времени всего +1 мс
      b.timestamp = t;
      b.merkleRoot = b.calculateMerkleRoot();
      b.hash = b.calculateHash();
      Assertions.assertTrue(b.mineBlock(diff));
      Assertions.assertTrue(service.applyIncomingBlock(b));
      prev = service.getLatestBlockHeader();
    }

    Assertions.assertEquals(10, prev.height);
    Assertions.assertEquals(config.difficulty, prev.difficulty, "Сложность не должна расти при замороженном MTP времени");
  }

  // Проверяет отклонение блоков с недействительным Proof-of-Work доказательством.
  @Test
  @DisplayName("PoW безопасность: отклонение блока с неверным хешем или недостаточной сложностью")
  public void testInvalidProofOfWorkRejected() {
    Block prev = service.getLatestBlockHeader();
    Block b = new Block(prev.height + 1, config.chainId, prev.hash, config.genesisAddress, List.of(), config.difficulty);
    b.timestamp = prev.timestamp + 1000L;
    b.nonce = 12345L;
    b.hash = "ffff" + b.calculateHash().substring(4); // Хеш без требуемых нулей

    Assertions.assertFalse(service.applyIncomingBlock(b));
  }

  // Проверяет отклонение попытки реорганизации глубже предельного лимита MAX_REORG_DEPTH (защита от дальних форков).
  @Test
  @DisplayName("Защита от 51%: отклонение реорганизации цепи глубиной более 20 блоков")
  public void testDeepReorganizationExceedingLimitRejected() {
    // Наращиваем основную цепочку до высоты 25 через штатный майнинг
    for (int i = 1; i <= 25; i++) {
      Block mined = service.mineBlock();
      Assertions.assertNotNull(mined, "Блок #" + i + " должен быть успешно смайнен");
    }
    Assertions.assertEquals(25L, service.getLatestBlockHeader().height);

    // Пытаемся применить форк от высоты 2 (глубина отката 23 > 20)
    List<Block> maliciousFork = new ArrayList<>();
    Block forkAnchor = service.getBlockByIdentifier("2").value;
    Assertions.assertNotNull(forkAnchor);
    Block forkPrev = forkAnchor;
    long t = forkAnchor.timestamp;
    for (long h = 3; h <= 27; h++) {
      Block fb = new Block(h, config.chainId, forkPrev.hash, config.genesisAddress, List.of(), config.difficulty);
      fb.timestamp = t + ((h - 2) * 1000L);
      fb.merkleRoot = fb.calculateMerkleRoot();
      fb.hash = fb.calculateHash();
      Assertions.assertTrue(fb.mineBlock(config.difficulty));
      maliciousFork.add(fb);
      forkPrev = fb;
    }

    boolean isReorg = service.reorganizeChain(maliciousFork);
    Assertions.assertFalse(isReorg, "Реорганизация глубже 20 блоков должна быть заблокирована");
    Assertions.assertEquals(25L, service.getLatestBlockHeader().height);
  }
}
