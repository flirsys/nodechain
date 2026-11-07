package so.fox.nodechain.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

// Конфигурация параметров ноды блокчейна со строгой валидацией консенсуса
@Configuration
public class NodeConfig {

  @Value("${server.port:8080}")
  public int serverPort;

  @Value("${node.p2p.port:9090}")
  public int p2pPort;

  @Value("${node.db.path:sqlite.db}")
  public String dbPath;

  @Value("${blockchain.chain-id:foxchain-main-1}")
  public String chainId;

  @Value("${blockchain.difficulty:2}")
  public int difficulty;

  @Value("${blockchain.mining-reward:100000000}")
  public long miningReward;

  @Value("${blockchain.auto-mine:true}")
  public boolean isAutoMine;

  @Value("${blockchain.mine-interval-seconds:10}")
  public int mineIntervalSeconds;

  @Value("${blockchain.max-mempool-size:10000}")
  public int maxMempoolSize;

  @Value("${blockchain.genesis-allocation:1000000000000}")
  public long genesisAllocation;

  @Value("${blockchain.genesis-address:}")
  public String genesisAddress;

  @Value("${blockchain.halving-interval:210000}")
  public long halvingInterval;

  @Value("${node.p2p.seeds:}")
  public String peerSeeds;

  // Валидирует параметры консенсуса и конфигурации при запуске ноды
  @PostConstruct
  public void validate() {
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
    if (maxMempoolSize <= 0) {
      throw new IllegalStateException("Параметр blockchain.max-mempool-size должен быть больше 0");
    }
    if (genesisAllocation < 0) {
      throw new IllegalStateException("Параметр blockchain.genesis-allocation не может быть отрицательным: " + genesisAllocation);
    }
    if (halvingInterval <= 0) {
      throw new IllegalStateException("Параметр blockchain.halving-interval должен быть больше 0: " + halvingInterval);
    }
  }
}
