package so.fox.nodechain.repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import so.fox.nodechain.model.Block;

// Репозиторий доступа к блокам блокчейна в базе данных SQLite с разделением на пулы чтения и записи.
@Repository
public class BlockRepository {

  private static final RowMapper<Block> ROW_MAPPER = (rs, i) -> new Block(new Block.Meta(
    rs.getLong("height"),
    rs.getString("chain_id"),
    rs.getString("hash"),
    rs.getString("previous_hash"),
    rs.getString("merkle_root"),
    rs.getString("miner_address"),
    rs.getInt("tx_count"),
    rs.getLong("timestamp"),
    rs.getInt("difficulty"),
    rs.getLong("nonce")
  ));

  private final JdbcTemplate writeJdbc;
  private final JdbcTemplate readJdbc;

  // Основной конструктор репозитория с инъекцией шаблонов записи и чтения.
  @Autowired
  public BlockRepository(
    @Qualifier("writeJdbcTemplate") JdbcTemplate writeJdbc,
    @Qualifier("readJdbcTemplate") JdbcTemplate readJdbc
  ) {
    this.writeJdbc = writeJdbc;
    this.readJdbc = readJdbc != null ? readJdbc : writeJdbc;
  }

  // Конструктор для совместимости с тестами на едином источнике данных.
  public BlockRepository(JdbcTemplate jdbcTemplate) {
    this(jdbcTemplate, jdbcTemplate);
  }

  // Сохраняет или обновляет заголовок блока в таблице blocks с поддержкой chain_id.
  public void save(Block b) {
    if (b == null || b.hash == null) return;
    b.hash = b.hash.trim().toLowerCase();
    if (b.previousHash != null) b.previousHash = b.previousHash.trim().toLowerCase();
    String miner = b.minerAddress != null ? b.minerAddress.trim() : "";
    if (!miner.isEmpty() && !"GENESIS".equalsIgnoreCase(miner)) miner = miner.toLowerCase();
    b.minerAddress = miner;
    writeJdbc.update("""
      INSERT OR REPLACE INTO blocks (height, chain_id, hash, previous_hash, merkle_root, miner_address, tx_count, timestamp, difficulty, nonce)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, b.height, b.chainId, b.hash, b.previousHash, b.merkleRoot,
      b.minerAddress, b.txCount, b.timestamp, b.difficulty, b.nonce);
  }

  // Находит блок по его высоте (номеру).
  public Optional<Block> findByHeight(long height) {
    return readJdbc.query("SELECT * FROM blocks WHERE height = ?", ROW_MAPPER, height).stream().findFirst();
  }

  // Находит блок по его криптографическому хешу.
  public Optional<Block> findByHash(String hash) {
    if (hash == null) return Optional.empty();
    return readJdbc.query("SELECT * FROM blocks WHERE hash = ?", ROW_MAPPER, hash.trim().toLowerCase()).stream().findFirst();
  }

  // Возвращает самый свежий блок из цепочки с максимальной высотой.
  public Optional<Block> findLatestBlock() {
    return readJdbc.query("SELECT * FROM blocks ORDER BY height DESC LIMIT 1", ROW_MAPPER).stream().findFirst();
  }

  // Возвращает общее количество сохраненных блоков в цепочке.
  public long count() {
    Long cnt = readJdbc.queryForObject("SELECT COUNT(*) FROM blocks", Long.class);
    return cnt != null ? cnt : 0L;
  }

  // Загружает диапазон блоков начиная с указанной высоты с ограничением лимита.
  public List<Block> findBlocksFrom(long fromHeight, int limit) {
    if (limit <= 0) return Collections.emptyList();
    return readJdbc.query(
      "SELECT * FROM blocks WHERE height >= ? ORDER BY height ASC LIMIT ?",
      ROW_MAPPER,
      fromHeight,
      limit
    );
  }

  // Загружает блоки в диапазоне высот [fromHeight, toHeight] включительно.
  public List<Block> findBlocksBetween(long fromHeight, long toHeight) {
    if (fromHeight > toHeight) return Collections.emptyList();
    return readJdbc.query(
      "SELECT * FROM blocks WHERE height >= ? AND height <= ? ORDER BY height ASC",
      ROW_MAPPER,
      fromHeight,
      toHeight
    );
  }

  // Удаляет все блоки с высотой строго больше заданной.
  public void deleteBlocksGreaterThan(long height) {
    writeJdbc.update("DELETE FROM blocks WHERE height > ?", height);
  }
}
