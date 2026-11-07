package so.fox.nodechain.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

// Конфигурация базы данных SQLite с пулом соединений HikariCP
@Configuration
public class DatabaseConfig {

  private final NodeConfig nodeConfig;

  public DatabaseConfig(NodeConfig nodeConfig) {
    this.nodeConfig = nodeConfig;
  }

  // Настройка пула соединений HikariCP для SQLite в режиме WAL с поддержкой параллельного чтения
  @Bean
  public DataSource dataSource() {
    HikariConfig cfg = new HikariConfig();
    cfg.setDriverClassName("org.sqlite.JDBC");
    cfg.setJdbcUrl("jdbc:sqlite:" + nodeConfig.dbPath);
    cfg.setConnectionInitSql("PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA busy_timeout = 10000;");
    cfg.setMaximumPoolSize(10);
    cfg.setConnectionTimeout(15000);
    cfg.setIdleTimeout(600000);
    cfg.setMaxLifetime(1800000);
    return new HikariDataSource(cfg);
  }

  // Инициализирует JdbcTemplate и схему таблиц блокчейна
  @Bean
  public JdbcTemplate jdbcTemplate(DataSource dataSource) {
    JdbcTemplate tmpl = new JdbcTemplate(dataSource);
    initDatabase(tmpl);
    return tmpl;
  }

  // Настройка менеджера транзакций для поддержки @Transactional на SQLite DataSource
  @Bean
  public PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  // Создает необходимые таблицы и индексы в SQLite
  public static void initDatabase(JdbcTemplate jdbc) {
    jdbc.execute("""
      CREATE TABLE IF NOT EXISTS blocks (
        height INTEGER PRIMARY KEY,
        chain_id TEXT NOT NULL,
        hash TEXT UNIQUE NOT NULL,
        previous_hash TEXT NOT NULL,
        merkle_root TEXT NOT NULL,
        miner_address TEXT NOT NULL,
        tx_count INTEGER NOT NULL,
        timestamp INTEGER NOT NULL,
        difficulty INTEGER NOT NULL,
        nonce INTEGER NOT NULL
      );
    """);

    jdbc.execute("""
      CREATE TABLE IF NOT EXISTS transactions (
        hash TEXT PRIMARY KEY,
        block_height INTEGER,
        block_hash TEXT,
        chain_id TEXT NOT NULL,
        tx_from TEXT NOT NULL,
        tx_to TEXT NOT NULL,
        amount INTEGER NOT NULL,
        fee INTEGER NOT NULL,
        nonce INTEGER NOT NULL,
        public_key TEXT NOT NULL,
        signature TEXT NOT NULL,
        comment TEXT,
        timestamp INTEGER NOT NULL,
        status TEXT NOT NULL,
        tx_index INTEGER DEFAULT 0
      );
    """);
    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tx_from ON transactions(tx_from);");
    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tx_to ON transactions(tx_to);");
    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tx_block_height ON transactions(block_height);");
    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tx_status ON transactions(status);");

    jdbc.execute("""
      CREATE TABLE IF NOT EXISTS accounts (
        address TEXT PRIMARY KEY,
        balance INTEGER NOT NULL,
        nonce INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );
    """);

    jdbc.execute("""
      CREATE TABLE IF NOT EXISTS peers (
        host TEXT NOT NULL,
        port INTEGER NOT NULL,
        last_seen INTEGER NOT NULL,
        PRIMARY KEY (host, port)
      );
    """);
  }
}
