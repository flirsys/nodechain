package so.fox.nodechain.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import so.fox.nodechain.model.Account;

// Репозиторий доступа к состоянию аккаунтов в базе данных SQLite.
@Repository
public class AccountRepository {

  private static final RowMapper<Account> ROW_MAPPER = (rs, i) -> {
    Account acc = new Account(rs.getString("address"), rs.getLong("balance"), rs.getLong("nonce"));
    acc.updatedAt = rs.getLong("updated_at");
    return acc;
  };

  private final JdbcTemplate jdbcTemplate;

  public AccountRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // Находит аккаунт по его fox-адресу.
  public Optional<Account> findByAddress(String addr) {
    if (addr == null) return Optional.empty();
    return jdbcTemplate.query("SELECT * FROM accounts WHERE address = ?", ROW_MAPPER, addr.trim().toLowerCase())
      .stream().findFirst();
  }

  // Удаляет все записи аккаунтов из базы данных.
  public void deleteAll() {
    jdbcTemplate.update("DELETE FROM accounts");
  }

  // Сохраняет новый или обновляет существующий аккаунт в базе данных.
  public void saveOrUpdate(Account acc) {
    if (acc == null || acc.address == null) return;
    acc.address = acc.address.trim().toLowerCase();
    jdbcTemplate.update("""
      INSERT OR REPLACE INTO accounts (address, balance, nonce, updated_at)
      VALUES (?, ?, ?, ?)
    """, acc.address, acc.balance, acc.nonce, acc.updatedAt);
  }
}
