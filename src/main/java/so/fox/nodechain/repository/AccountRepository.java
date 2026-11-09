package so.fox.nodechain.repository;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import so.fox.nodechain.model.Account;

// Репозиторий доступа к состоянию аккаунтов в базе данных SQLite с разделением на пулы чтения и записи.
@Repository
public class AccountRepository {

  private static final RowMapper<Account> ROW_MAPPER = (rs, i) -> {
    Account acc = new Account(rs.getString("address"), rs.getLong("balance"), rs.getLong("nonce"));
    acc.updatedAt = rs.getLong("updated_at");
    return acc;
  };

  private final JdbcTemplate writeJdbc;
  private final JdbcTemplate readJdbc;

  // Основной конструктор репозитория с инъекцией шаблонов записи и чтения.
  @Autowired
  public AccountRepository(
    @Qualifier("writeJdbcTemplate") JdbcTemplate writeJdbc,
    @Qualifier("readJdbcTemplate") JdbcTemplate readJdbc
  ) {
    this.writeJdbc = writeJdbc;
    this.readJdbc = readJdbc != null ? readJdbc : writeJdbc;
  }

  // Конструктор для совместимости с тестами на едином источнике данных.
  public AccountRepository(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, jdbcTemplate);
  }

  // Находит аккаунт по его fox-адресу.
  public Optional<Account> findByAddress(String addr) {
    if (addr == null) return Optional.empty();
    return readJdbc.query("SELECT * FROM accounts WHERE address = ?", ROW_MAPPER, addr.trim().toLowerCase())
      .stream().findFirst();
  }

  // Удаляет все записи аккаунтов из базы данных.
  public void deleteAll() {
    writeJdbc.update("DELETE FROM accounts");
  }

  // Сохраняет новый или обновляет существующий аккаунт в базе данных.
  public void saveOrUpdate(Account acc) {
    if (acc == null || acc.address == null) return;
    acc.address = acc.address.trim().toLowerCase();
    writeJdbc.update("""
      INSERT OR REPLACE INTO accounts (address, balance, nonce, updated_at)
      VALUES (?, ?, ?, ?)
    """, acc.address, acc.balance, acc.nonce, acc.updatedAt);
  }
}
