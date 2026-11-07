package so.fox.nodechain.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import so.fox.nodechain.model.PeerInfo;

// Репозиторий хранения и обнаружения сетевых пиров P2P в SQLite
@Repository
public class PeerRepository {

  private static final RowMapper<PeerInfo> ROW_MAPPER = (rs, i) -> new PeerInfo(
    rs.getString("host"),
    rs.getInt("port"),
    rs.getLong("last_seen")
  );

  private final JdbcTemplate jdbcTemplate;

  public PeerRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  // Возвращает список всех известных пиров по времени последней активности
  public List<PeerInfo> findAll() {
    return jdbcTemplate.query("SELECT * FROM peers ORDER BY last_seen DESC", ROW_MAPPER);
  }

  // Возвращает общее количество известных пиров в базе данных
  public long count() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM peers", Long.class);
    return count != null ? count : 0L;
  }

  // Сохраняет или обновляет отметку времени активности пира
  public void saveOrUpdate(PeerInfo peer) {
    if (peer == null) return;
    jdbcTemplate.update(
      "INSERT OR REPLACE INTO peers (host, port, last_seen) VALUES (?, ?, ?)",
      peer.host(), peer.port(), peer.lastSeen()
    );
  }

  // Удаляет неактивного пира из базы данных
  public void delete(String host, int port) {
    if (host == null) return;
    jdbcTemplate.update("DELETE FROM peers WHERE host = ? AND port = ?", host, port);
  }
}
