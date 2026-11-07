package so.fox.nodechain.repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import so.fox.nodechain.model.Block;

// Репозиторий доступа к блокам блокчейна в базе данных SQLite
@Repository
public class BlockRepository {

  private final JdbcTemplate jdbcTemplate;

  private static final RowMapper<Block> ROW_MAPPER = (rs, i) -> new Block(new Block.Meta(
    rs.getLong("height"),
    rs.getString("hash"),
    rs.getString("previous_hash"),
    rs.getString("merkle_root"),
    rs.getString("miner_address"),
    rs.getInt("tx_count"),
    rs.getLong("timestamp"),
    rs.getLong("nonce")
  ));

  public BlockRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // Сохраняет заголовок блока в таблицу blocks
  public void save(Block b) {
    if (b == null) return;
    jdbcTemplate.update("""
      INSERT OR REPLACE INTO blocks (height, hash, previous_hash, merkle_root, miner_address, tx_count, timestamp, nonce)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """, b.height, b.hash, b.previousHash, b.merkleRoot,
      b.minerAddress != null ? b.minerAddress : "",
      b.txCount, b.timestamp, b.nonce);
  }

  // Находит блок по его высоте (номеру)
  public Optional<Block> findByHeight(long height) {
    return jdbcTemplate.query("SELECT * FROM blocks WHERE height = ?", ROW_MAPPER, height).stream().findFirst();
  }

  // Находит блок по его криптографическому хешу
  public Optional<Block> findByHash(String hash) {
    if (hash == null) return Optional.empty();
    return jdbcTemplate.query("SELECT * FROM blocks WHERE hash = ?", ROW_MAPPER, hash).stream().findFirst();
  }

  // Возвращает самый свежий блок из цепочки с максимальной высотой
  public Optional<Block> findLatestBlock() {
    return jdbcTemplate.query("SELECT * FROM blocks ORDER BY height DESC LIMIT 1", ROW_MAPPER).stream().findFirst();
  }

  // Возвращает общее количество сохраненных блоков в цепочке
  public long count() {
    Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM blocks", Long.class);
    return cnt != null ? cnt : 0L;
  }

  // Загружает диапазон блоков начиная с указанной высоты с ограничением лимита
  public List<Block> findBlocksFrom(long fromHeight, int limit) {
    if (limit <= 0) return Collections.emptyList();
    return jdbcTemplate.query(
      "SELECT * FROM blocks WHERE height >= ? ORDER BY height ASC LIMIT ?",
      ROW_MAPPER,
      fromHeight,
      limit
    );
  }

  // Удаляет все блоки с высотой строго больше заданной
  public void deleteBlocksGreaterThan(long height) {
    jdbcTemplate.update("DELETE FROM blocks WHERE height > ?", height);
  }
}
