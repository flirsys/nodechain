package so.fox.nodechain.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import so.fox.nodechain.model.Account;

// Репозиторий доступа к состоянию аккаунтов в базе данных SQLite
@Repository
public class AccountRepository {

  private final JdbcTemplate jdbcTemplate;

  private static final RowMapper<Account> ROW_MAPPER = (rs, i) -> {
    Account acc = new Account(rs.getString("address"), rs.getLong("balance"), rs.getLong("nonce"));
    acc.updatedAt = rs.getLong("updated_at");
    return acc;
  };

  public AccountRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // Находит аккаунт по его fox-адресу
  public Optional<Account> findByAddress(String addr) {
    if (addr == null) return Optional.empty();
    return jdbcTemplate.query("SELECT * FROM accounts WHERE address = ?", ROW_MAPPER, addr)
      .stream().findFirst();
  }

  // Сохраняет новый или обновляет существующий аккаунт в базе данных
  public void saveOrUpdate(Account acc) {
    if (acc == null) return;
    jdbcTemplate.update("""
      INSERT OR REPLACE INTO accounts (address, balance, nonce, updated_at)
      VALUES (?, ?, ?, ?)
    """, acc.address, acc.balance, acc.nonce, acc.updatedAt);
  }
}
