package so.fox.nodechain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import so.fox.nodechain.crypto.CryptoUtil;

// Модель блока блокчейна: заголовок, Proof-of-Work доказательство и список транзакций
@JsonIgnoreProperties(ignoreUnknown = true)
public class Block implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(Block.class);

  public long height;
  public String chainId;
  public String hash;
  public String previousHash;
  public String merkleRoot;
  public String minerAddress;
  public int txCount;
  public long timestamp;
  public int difficulty = 4;
  public long nonce;

  @JsonSetter(nulls = Nulls.AS_EMPTY)
  public List<Transaction> transactions = new ArrayList<>();

  public record Meta(long height, String chainId, String hash, String previousHash, String merkleRoot, String minerAddress, int txCount, long timestamp, int difficulty, long nonce) {}

  public Block() {}

  // Конструктор создания блока перед майнингом с обязательным chainId
  public Block(long height, String chainId, String previousHash, String minerAddress, List<Transaction> txs, int difficulty) {
    this.height = height;
    this.chainId = chainId != null ? chainId.trim() : "";
    this.previousHash = previousHash != null ? previousHash : "";
    this.minerAddress = minerAddress != null ? minerAddress : "";
    this.transactions = txs != null ? new ArrayList<>(txs) : new ArrayList<>();
    this.txCount = this.transactions.size();
    this.timestamp = System.currentTimeMillis();
    this.difficulty = Math.max(1, difficulty);
    this.nonce = 0;
    this.merkleRoot = calculateMerkleRoot();
    this.hash = calculateHash();
  }

  // Конструктор восстановления блока из репозитория
  public Block(Meta meta) {
    this.height = meta.height();
    this.chainId = meta.chainId();
    this.hash = meta.hash();
    this.previousHash = meta.previousHash();
    this.merkleRoot = meta.merkleRoot();
    this.minerAddress = meta.minerAddress() != null ? meta.minerAddress() : "";
    this.txCount = meta.txCount();
    this.timestamp = meta.timestamp();
    this.difficulty = meta.difficulty();
    this.nonce = meta.nonce();
    this.transactions = new ArrayList<>();
  }

  // Создает Genesis-блок #0 строго с привязкой к chainId
  public static Block createGenesis(String chainId, String minerAddress, int difficulty, long timestamp) {
    Block genesis = new Block();
    genesis.height = 0;
    genesis.chainId = chainId != null ? chainId.trim() : "";
    genesis.previousHash = "0";
    genesis.minerAddress = (minerAddress != null && !minerAddress.isBlank()) ? minerAddress : "GENESIS";
    genesis.transactions = Collections.emptyList();
    genesis.txCount = 0;
    genesis.timestamp = Math.max(0L, timestamp);
    genesis.difficulty = Math.max(1, difficulty);
    genesis.nonce = 0;
    genesis.merkleRoot = "0";
    genesis.hash = genesis.calculateHash();
    return genesis;
  }

  // Вычисляет SHA-256 хеш блока строго включая chainId сети
  public String calculateHash() {
    String payload = height + "|" + chainId + "|" + previousHash + "|" + (merkleRoot != null ? merkleRoot : "") + "|"
      + (minerAddress != null ? minerAddress : "") + "|" + timestamp + "|" + difficulty + "|" + nonce;
    return CryptoUtil.sha256Hex(payload);
  }

  // Строит дерево Меркла над хешами транзакций блока с доменным разделением (RFC 6962)
  public String calculateMerkleRoot() {
    if (transactions == null || transactions.isEmpty()) return "0";
    List<String> tree = new ArrayList<>();
    for (Transaction tx : transactions) {
      if (tx == null || tx.hash == null || tx.hash.isEmpty()) return "0";
      tree.add(CryptoUtil.sha256Hex("00" + tx.hash));
    }

    while (tree.size() > 1) {
      if (tree.size() % 2 != 0) tree.add(tree.get(tree.size() - 1));
      List<String> nextLevel = new ArrayList<>();
      for (int i = 0; i < tree.size(); i += 2) {
        String combined = "01" + tree.get(i) + tree.get(i + 1);
        nextLevel.add(CryptoUtil.sha256Hex(combined));
      }
      tree = nextLevel;
    }
    return tree.get(0);
  }

  // Выполняет подбор Proof-of-Work nonce до достижения нужной сложности
  public boolean mineBlock(int difficulty) {
    this.difficulty = Math.max(1, difficulty);
    String target = "0".repeat(this.difficulty);
    this.hash = calculateHash();
    long start = System.currentTimeMillis();
    while (!this.hash.startsWith(target)) {
      if (Thread.currentThread().isInterrupted()) {
        log.warn("Mining interrupted for block #{}", height);
        return false;
      }
      this.nonce++;
      this.hash = calculateHash();
    }
    long elapsed = System.currentTimeMillis() - start;
    log.info("Block mined: height={}, nonce={}, difficulty={}, time={}ms", height, nonce, this.difficulty, elapsed);
    return true;
  }

  // Устанавливает список транзакций блока и синхронизирует счетчик txCount
  public void setTransactions(List<Transaction> txs) {
    this.transactions = txs != null ? txs : new ArrayList<>();
    this.txCount = this.transactions.size();
  }
}
