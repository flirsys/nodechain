package so.fox.nodechain.repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import so.fox.nodechain.model.Transaction;

// Репозиторий доступа к транзакциям блокчейна в базе данных SQLite
@Repository
public class TransactionRepository {

  private final JdbcTemplate jdbcTemplate;

  private static final RowMapper<Transaction> ROW_MAPPER = (rs, i) -> {
    Transaction tx = new Transaction();
    tx.hash = rs.getString("hash");
    long blockHeight = rs.getLong("block_height");
    tx.blockHeight = rs.wasNull() ? null : blockHeight;
    tx.blockHash = rs.getString("block_hash");
    tx.chainId = rs.getString("chain_id");
    tx.from = rs.getString("tx_from");
    tx.to = rs.getString("tx_to");
    tx.amount = rs.getLong("amount");
    tx.fee = rs.getLong("fee");
    tx.nonce = rs.getLong("nonce");
    tx.publicKey = rs.getString("public_key");
    tx.signature = rs.getString("signature");
    tx.comment = rs.getString("comment");
    tx.timestamp = rs.getLong("timestamp");
    tx.status = rs.getString("status");
    tx.txIndex = rs.getInt("tx_index");
    return tx;
  };

  public TransactionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // Сохраняет новую или обновляет существующую транзакцию в базе данных
  public void save(Transaction tx) {
    if (tx == null) return;
    jdbcTemplate.update("""
      INSERT OR REPLACE INTO transactions (hash, block_height, block_hash, chain_id, tx_from, tx_to,
        amount, fee, nonce, public_key, signature, comment, timestamp, status, tx_index)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, tx.hash, tx.blockHeight, tx.blockHash, tx.chainId, tx.from, tx.to,
      tx.amount, tx.fee, tx.nonce, tx.publicKey, tx.signature,
      tx.comment, tx.timestamp, tx.status, tx.txIndex);
  }

  // Находит транзакцию по ее криптографическому хешу
  public Optional<Transaction> findByHash(String hash) {
    if (hash == null) return Optional.empty();
    return jdbcTemplate.query("SELECT * FROM transactions WHERE hash = ?", ROW_MAPPER, hash).stream().findFirst();
  }

  // Возвращает список всех транзакций блока строго по порядку их индексов
  public List<Transaction> findByBlockHeight(long blockHeight) {
    return jdbcTemplate.query(
      "SELECT * FROM transactions WHERE block_height = ? ORDER BY tx_index ASC, rowid ASC",
      ROW_MAPPER,
      blockHeight
    );
  }

  // Возвращает список транзакций адреса с пагинацией
  public List<Transaction> findByAddress(String addr, int limit, int offset) {
    if (addr == null || limit <= 0) return Collections.emptyList();
    return jdbcTemplate.query("""
      SELECT * FROM transactions
      WHERE tx_from = ? OR tx_to = ?
      ORDER BY timestamp DESC
      LIMIT ? OFFSET ?
    """, ROW_MAPPER, addr, addr, limit, Math.max(0, offset));
  }

  // Возвращает общее количество транзакций адреса
  public long countByAddress(String addr) {
    if (addr == null) return 0L;
    Long cnt = jdbcTemplate.queryForObject(
      "SELECT COUNT(*) FROM transactions WHERE tx_from = ? OR tx_to = ?",
      Long.class, addr, addr
    );
    return cnt != null ? cnt : 0L;
  }

  // Возвращает список неподтвержденных транзакций со статусом pending
  public List<Transaction> findPending() {
    return jdbcTemplate.query(
      "SELECT * FROM transactions WHERE status = 'pending' ORDER BY timestamp ASC",
      ROW_MAPPER
    );
  }
}
