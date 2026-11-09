package so.fox.nodechain.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import so.fox.nodechain.model.Transaction;

// Репозиторий доступа к транзакциям блокчейна в базе данных SQLite с разделением на пулы чтения и записи.
@Repository
public class TransactionRepository {

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

  private final JdbcTemplate writeJdbc;
  private final JdbcTemplate readJdbc;

  // Основной конструктор репозитория с инъекцией шаблонов записи и чтения.
  @Autowired
  public TransactionRepository(
    @Qualifier("writeJdbcTemplate") JdbcTemplate writeJdbc,
    @Qualifier("readJdbcTemplate") JdbcTemplate readJdbc
  ) {
    this.writeJdbc = writeJdbc;
    this.readJdbc = readJdbc != null ? readJdbc : writeJdbc;
  }

  // Конструктор для совместимости с тестами на едином источнике данных.
  public TransactionRepository(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, jdbcTemplate);
  }

  // Сохраняет или обновляет транзакцию в базе данных.
  public void save(Transaction tx) {
    if (tx == null || tx.hash == null) return;
    tx.hash = tx.hash.trim().toLowerCase();
    if (tx.blockHash != null) tx.blockHash = tx.blockHash.trim().toLowerCase();
    if (tx.from != null) tx.from = tx.from.trim().toLowerCase();
    if (tx.to != null) tx.to = tx.to.trim().toLowerCase();
    writeJdbc.update("""
      INSERT OR REPLACE INTO transactions (hash, block_height, block_hash, chain_id, tx_from, tx_to,
        amount, fee, nonce, public_key, signature, comment, timestamp, status, tx_index)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, tx.hash, tx.blockHeight, tx.blockHash, tx.chainId, tx.from, tx.to,
      tx.amount, tx.fee, tx.nonce, tx.publicKey, tx.signature,
      tx.comment, tx.timestamp, tx.status, tx.txIndex);
  }

  // Находит транзакцию по ее криптографическому хешу.
  public Optional<Transaction> findByHash(String hash) {
    if (hash == null) return Optional.empty();
    return readJdbc.query("SELECT * FROM transactions WHERE hash = ?", ROW_MAPPER, hash.trim().toLowerCase()).stream().findFirst();
  }

  // Возвращает подмножество переданных хешей, которые уже подтверждены блоком.
  public Set<String> findConfirmedHashes(Collection<String> hashes) {
    if (hashes == null || hashes.isEmpty()) return Set.of();
    List<String> list = new ArrayList<>(hashes);
    Set<String> found = new HashSet<>();
    for (int from = 0; from < list.size(); from += 500) {
      List<String> chunk = list.subList(from, Math.min(list.size(), from + 500));
      String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
      found.addAll(readJdbc.queryForList(
        "SELECT hash FROM transactions WHERE status = 'confirmed' AND hash IN (" + placeholders + ")",
        String.class, chunk.toArray()
      ));
    }
    return found;
  }

  // Возвращает список всех подтвержденных транзакций блока строго по порядку их индексов.
  public List<Transaction> findByBlockHeight(long blockHeight) {
    return readJdbc.query(
      "SELECT * FROM transactions WHERE block_height = ? AND status = 'confirmed' ORDER BY tx_index ASC, rowid ASC",
      ROW_MAPPER,
      blockHeight
    );
  }

  // Возвращает подтвержденные транзакции блоков в диапазоне высот [fromHeight, toHeight].
  public List<Transaction> findByBlockHeightBetween(long fromHeight, long toHeight) {
    if (fromHeight > toHeight) return Collections.emptyList();
    return readJdbc.query(
      "SELECT * FROM transactions WHERE block_height >= ? AND block_height <= ? AND status = 'confirmed' ORDER BY block_height ASC, tx_index ASC, rowid ASC",
      ROW_MAPPER,
      fromHeight,
      toHeight
    );
  }

  // Возвращает список транзакций адреса с пагинацией.
  public List<Transaction> findByAddress(String addr, int limit, int offset) {
    if (addr == null || limit <= 0) return Collections.emptyList();
    String norm = addr.trim().toLowerCase();
    return readJdbc.query("""
      SELECT * FROM transactions
      WHERE tx_from = ? OR tx_to = ?
      ORDER BY timestamp DESC, nonce DESC, hash ASC
      LIMIT ? OFFSET ?
    """, ROW_MAPPER, norm, norm, limit, Math.max(0, offset));
  }

  // Возвращает общее количество транзакций адреса.
  public long countByAddress(String addr) {
    if (addr == null) return 0L;
    String norm = addr.trim().toLowerCase();
    Long cnt = readJdbc.queryForObject(
      "SELECT COUNT(*) FROM transactions WHERE tx_from = ? OR tx_to = ?",
      Long.class, norm, norm
    );
    return cnt != null ? cnt : 0L;
  }

  // Возвращает список неподтвержденных транзакций со статусом pending.
  public List<Transaction> findPending() {
    return readJdbc.query(
      "SELECT * FROM transactions WHERE status = 'pending' ORDER BY timestamp ASC, nonce ASC, hash ASC",
      ROW_MAPPER
    );
  }

  // Полностью очищает таблицу транзакций в базе данных.
  public void deleteAll() {
    writeJdbc.update("DELETE FROM transactions");
  }
}
