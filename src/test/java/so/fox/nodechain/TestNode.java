package so.fox.nodechain;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import so.fox.nodechain.config.DatabaseConfig;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.core.BlockchainService;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.p2p.P2pClientManager;
import so.fox.nodechain.repository.AccountRepository;
import so.fox.nodechain.repository.BlockRepository;
import so.fox.nodechain.repository.PeerRepository;
import so.fox.nodechain.repository.TransactionRepository;

// Автономный изолированный узел блокчейна для многоузлового тестирования P2P сети.
public class TestNode implements AutoCloseable {

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

  // Запускает блокчейн-сервис и сетевой P2P сервер узла.
  public void start() {
    blockchain.init();
    p2p.start();
  }

  // Корректно останавливает P2P сервер, блокчейн и освобождает БД.
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

  // Очищает файлы базы данных и кошелька узла.
  private void cleanFiles() {
    new File(dbFile).delete();
    new File(dbFile + "-wal").delete();
    new File(dbFile + "-shm").delete();
    try {
      Files.deleteIfExists(walletFile);
    } catch (IOException ignored) {}
  }
}
