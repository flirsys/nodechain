package so.fox.nodechain.config;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import so.fox.nodechain.crypto.CryptoUtil;

// Конфигурация параметров ноды блокчейна со строгой валидацией консенсуса.
@Configuration
public class NodeConfig {

  @Value("${server.port:8080}")
  public int serverPort = 8080;

  @Value("${node.p2p.port:9090}")
  public int p2pPort = 9090;

  @Value("${node.db.path:sqlite.db}")
  public String dbPath = "sqlite.db";

  @Value("${blockchain.chain-id:foxchain-main-1}")
  public String chainId = "foxchain-main-1";

  @Value("${blockchain.difficulty:4}")
  public int difficulty = 4;

  @Value("${blockchain.difficulty-adjustment-interval:10}")
  public int difficultyAdjustmentInterval = 10;

  @Value("${blockchain.target-block-time-seconds:10}")
  public int targetBlockTimeSeconds = 10;

  @Value("${blockchain.min-difficulty:1}")
  public int minDifficulty = 1;

  @Value("${blockchain.max-difficulty:16}")
  public int maxDifficulty = 16;

  @Value("${blockchain.mining-reward:100000000}")
  public long miningReward = 100_000_000L;

  @Value("${blockchain.auto-mine:true}")
  public boolean isAutoMine = true;

  @Value("${blockchain.mine-interval-seconds:10}")
  public int mineIntervalSeconds = 10;

  @Value("${blockchain.max-mempool-size:10000}")
  public int maxMempoolSize = 10_000;

  @Value("${blockchain.genesis-allocation:1000000000000}")
  public long genesisAllocation = 1_000_000_000_000L;

  @Value("${blockchain.genesis-address:}")
  public String genesisAddress = "";

  @Value("${blockchain.halving-interval:210000}")
  public long halvingInterval = 210_000L;

  @Value("${node.p2p.seeds:}")
  public String peerSeeds = "";

  @Value("${blockchain.checkpoints:}")
  public String checkpointsRaw = "";

  // Разобранные чекпоинты, заполняются один раз при старте
  public Map<Long, String> checkpoints = Map.of();

  // Разбирает чекпоинты сети из конфигурации в неизменяемую карту высота -> хеш.
  public Map<Long, String> parseCheckpoints() {
    if (checkpointsRaw == null || checkpointsRaw.isBlank()) return Map.of();
    Map<Long, String> map = new HashMap<>();
    for (String el : checkpointsRaw.split(",")) {
      String[] parts = el.trim().split(":");
      if (parts.length != 2 || !parts[0].matches("^\\d{1,18}$")) continue;
      map.put(Long.parseLong(parts[0]), parts[1].trim().toLowerCase());
    }
    return Map.copyOf(map);
  }

  // Валидирует параметры консенсуса и конфигурации при запуске ноды.
  @PostConstruct
  public void validate() {
    checkpoints = parseCheckpoints();
    if (difficulty < 1 || difficulty > 32) {
      throw new IllegalStateException("Параметр blockchain.difficulty должен быть в диапазоне от 1 до 32: " + difficulty);
    }
    if (miningReward < 0) {
      throw new IllegalStateException("Параметр blockchain.mining-reward не может быть отрицательным: " + miningReward);
    }
    if (chainId == null || chainId.isBlank()) {
      throw new IllegalStateException("Параметр blockchain.chain-id не может быть пустым");
    }
    if (p2pPort <= 0 || p2pPort > 65535) {
      throw new IllegalStateException("Некорректный порт P2P: " + p2pPort);
    }
    if (serverPort < 0 || serverPort > 65535) {
      throw new IllegalStateException("Некорректный порт HTTP сервера: " + serverPort);
    }
    if (serverPort > 0 && serverPort == p2pPort) {
      throw new IllegalStateException("Порты server.port и node.p2p.port не должны совпадать: " + serverPort);
    }
    if (dbPath == null || dbPath.isBlank()) {
      throw new IllegalStateException("Параметр node.db.path не может быть пустым");
    }
    if (difficultyAdjustmentInterval <= 0) {
      throw new IllegalStateException("Параметр blockchain.difficulty-adjustment-interval должен быть больше 0: " + difficultyAdjustmentInterval);
    }
    if (maxMempoolSize <= 0) {
      throw new IllegalStateException("Параметр blockchain.max-mempool-size должен быть больше 0");
    }
    if (genesisAllocation < 0) {
      throw new IllegalStateException("Параметр blockchain.genesis-allocation не может быть отрицательным: " + genesisAllocation);
    }
    if (halvingInterval <= 0) {
      throw new IllegalStateException("Параметр blockchain.halving-interval должен быть больше 0: " + halvingInterval);
    }
    if (minDifficulty < 1 || maxDifficulty > 32 || minDifficulty > maxDifficulty) {
      throw new IllegalStateException("Некорректные границы min/max difficulty");
    }
    if (targetBlockTimeSeconds <= 0) {
      throw new IllegalStateException("Параметр blockchain.target-block-time-seconds должен быть больше 0");
    }
    if (genesisAddress != null && !genesisAddress.isBlank() && !CryptoUtil.isValidAddress(genesisAddress.trim())) {
      throw new IllegalStateException("Некорректный формат blockchain.genesis-address: " + genesisAddress);
    }
    if (difficulty < minDifficulty || difficulty > maxDifficulty) {
      throw new IllegalStateException("Значение blockchain.difficulty должно быть в диапазоне [minDifficulty, maxDifficulty]");
    }
    if (mineIntervalSeconds <= 0) {
      throw new IllegalStateException("Значение blockchain.mine-interval-seconds должно быть больше 0");
    }
  }
}
