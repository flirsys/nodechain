package so.fox.nodechain.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;

// Единые правила и валидаторы консенсуса сети блокчейна NodeChain (Static-First).
public final class ConsensusUtil {

  public static final long MIN_RELAY_FEE = 1L;
  public static final long MAX_FUTURE_BLOCK_TIME_MS = 30_000L;
  public static final int MAX_BLOCK_TXS = 500;
  public static final int MTP_WINDOW = 11;

  private ConsensusUtil() {}

  // Создает фабрику фоновых потоков-демонов с заданным именем.
  public static ThreadFactory daemonFactory(String name) {
    return r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    };
  }

  // Вычисляет Median Time Past по последним блокам окна MTP.
  public static long calculateMedianTimePast(List<Block> recent) {
    if (recent == null || recent.isEmpty()) return 0L;
    List<Long> times = new ArrayList<>();
    for (Block el : recent) {
      if (el != null) times.add(el.timestamp);
    }
    times.sort(null);
    return times.get(times.size() / 2);
  }

  // Проверяет заголовок блока, PoW, MTP, лимит транзакций и соответствие chainId.
  public static boolean isValidBlockHeader(
    Block prev, Block next, int difficulty, long maxTime, String expectedChainId, long mtp
  ) {
    if (next == null || prev == null) return false;
    if (next.height != prev.height + 1) return false;
    if (!prev.hash.equals(next.previousHash)) return false;
    if (next.timestamp <= 0) return false;
    if (next.timestamp <= prev.timestamp) return false;
    if (next.timestamp <= mtp) return false;
    if (next.timestamp > maxTime) return false;
    if (prev.chainId != null && next.chainId != null && !prev.chainId.equals(next.chainId)) return false;
    return isBlockContentAndPowValid(next, difficulty, expectedChainId);
  }

  // Проверяет базовую целостность и Proof-of-Work анонсированного по сети блока.
  public static boolean isValidAnnouncedBlock(Block b, NodeConfig cfg) {
    if (b == null || cfg == null || b.height <= 0 || b.timestamp <= 0) return false;
    if (b.timestamp > System.currentTimeMillis() + MAX_FUTURE_BLOCK_TIME_MS) return false;
    if (b.previousHash == null || !b.previousHash.matches("^[0-9a-f]{64}$")) return false;
    if (b.difficulty < cfg.minDifficulty || b.difficulty > cfg.maxDifficulty) return false;
    return isBlockContentAndPowValid(b, b.difficulty, cfg.chainId);
  }

  private static boolean isBlockContentAndPowValid(Block b, int expectedDifficulty, String expectedChainId) {
    if (expectedChainId != null && !expectedChainId.isBlank() && !expectedChainId.equals(b.chainId)) return false;
    if (b.height > 0 && (!CryptoUtil.isValidAddress(b.minerAddress) || !b.minerAddress.equals(b.minerAddress.toLowerCase()))) return false;
    if (b.transactions == null || b.txCount != b.transactions.size() || b.txCount > MAX_BLOCK_TXS) return false;
    if (b.difficulty != expectedDifficulty || b.nonce < 0) return false;
    if (b.hash == null || !isValidProofOfWork(b.hash, expectedDifficulty)) return false;
    if (!b.hash.equals(b.calculateHash())) return false;
    return b.merkleRoot != null && b.merkleRoot.equals(b.calculateMerkleRoot());
  }

  // Вычисляет ожидаемую сложность следующего блока (Bitcoin-style Difficulty Adjustment).
  public static int calculateNextDifficulty(Block prev, Block intervalAnchor, NodeConfig config, long mtpAnchor, long mtpPrev) {
    if (prev == null || prev.height == 0) return config.difficulty;
    int interval = config.difficultyAdjustmentInterval;
    long nextHeight = prev.height + 1;
    if (interval <= 0 || nextHeight % interval != 0) {
      return prev.difficulty > 0 ? prev.difficulty : config.difficulty;
    }
    if (intervalAnchor == null || prev.height <= intervalAnchor.height) {
      return prev.difficulty > 0 ? prev.difficulty : config.difficulty;
    }

    long blocksSpan = Math.max(1L, prev.height - intervalAnchor.height);
    long targetTimeSpan = blocksSpan * (long) config.targetBlockTimeSeconds * 1000L;
    long anchorTime = (mtpAnchor > 0) ? mtpAnchor : intervalAnchor.timestamp;
    long actualTimeSpan = Math.max(1L, mtpPrev - anchorTime);
    int currentDiff = prev.difficulty > 0 ? prev.difficulty : config.difficulty;

    if (actualTimeSpan < targetTimeSpan / 2) {
      return Math.min(config.maxDifficulty, currentDiff + 1);
    }
    if (actualTimeSpan > targetTimeSpan * 2) {
      return Math.max(config.minDifficulty, currentDiff - 1);
    }
    return currentDiff;
  }

  // Проверяет соответствие хеша блока заданной сложности PoW.
  public static boolean isValidProofOfWork(String hash, int difficulty) {
    if (hash == null) return false;
    String target = "0".repeat(Math.max(1, difficulty));
    return hash.startsWith(target);
  }

  // Проверяет формат и хеш транзакции без тяжелой криптографической валидации подписи.
  public static Result<Void> validateTransactionFormat(Transaction tx) {
    if (tx == null || tx.from == null || tx.to == null) return Result.fail(409, "Транзакция отклонена");
    if (!tx.from.equals(tx.from.trim()) || !tx.to.equals(tx.to.trim())) return Result.fail(400, "Некорректный формат адреса: пробелы по краям");
    if (tx.comment == null) tx.comment = "";
    else if (!tx.comment.equals(tx.comment.trim())) return Result.fail(400, "Комментарий содержит пробелы по краям");
    if (tx.chainId != null && !tx.chainId.equals(tx.chainId.trim())) return Result.fail(400, "Идентификатор сети содержит пробелы по краям");
    if (tx.publicKey != null && !tx.publicKey.equals(tx.publicKey.trim())) return Result.fail(400, "Открытый ключ содержит пробелы по краям");
    if (tx.signature != null && !tx.signature.equals(tx.signature.trim())) return Result.fail(400, "Подпись содержит пробелы по краям");
    tx.from = tx.from.toLowerCase();
    tx.to = tx.to.toLowerCase();
    if (!CryptoUtil.isValidAddress(tx.from) || !CryptoUtil.isValidAddress(tx.to)) return Result.fail(400, "Некорректный формат адреса");
    if (tx.from.equalsIgnoreCase(tx.to)) return Result.fail(409, "Транзакция отклонена: отправка самому себе");
    if (tx.amount <= 0) return Result.fail(409, "Транзакция отклонена: некорректная сумма");
    if (tx.fee < MIN_RELAY_FEE) return Result.fail(409, "Транзакция отклонена: некорректная комиссия");
    if (tx.amount > Long.MAX_VALUE - tx.fee) return Result.fail(409, "Транзакция отклонена: переполнение суммы и комиссии");
    if (tx.nonce < 0) return Result.fail(409, "Транзакция отклонена: некорректный nonce");
    if (tx.timestamp <= 0 || tx.timestamp > System.currentTimeMillis() + 600_000L) {
      return Result.fail(409, "Транзакция отклонена: некорректное время");
    }
    if (tx.comment != null && tx.comment.length() > 256) return Result.fail(409, "Транзакция отклонена: слишком длинный комментарий");
    if (tx.hash == null || !tx.hash.equals(tx.calculateHash())) return Result.fail(409, "Транзакция отклонена: неверный хеш");
    return Result.ok(null);
  }

  // Проверяет базовую корректность полей и цифровой подписи транзакции строго с учетом chainId.
  public static Result<Void> validateTransactionBasics(Transaction tx, String expectedChainId) {
    Result<Void> formatRes = validateTransactionFormat(tx);
    if (formatRes.isError()) return formatRes;
    if (expectedChainId == null || expectedChainId.isBlank() || tx.chainId == null || !expectedChainId.equals(tx.chainId)) {
      return Result.fail(409, "Транзакция отклонена: неверный chainId");
    }
    if (!tx.isSignatureValid(expectedChainId)) return Result.fail(400, "Подпись не верна");
    return Result.ok(null);
  }

  // Проверяет уникальность хешей транзакций внутри списка одного блока.
  public static boolean isTransactionsUnique(List<Transaction> txs) {
    if (txs == null || txs.isEmpty()) return true;
    Set<String> seen = new HashSet<>();
    for (Transaction tx : txs) {
      if (tx == null || tx.hash == null || !seen.add(tx.hash)) return false;
    }
    return true;
  }

  // Вычисляет PoW-работу для одного блока: 16^difficulty.
  public static BigInteger calculateBlockWork(int difficulty) {
    if (difficulty <= 0) return BigInteger.ONE;
    return BigInteger.valueOf(16).pow(difficulty);
  }

  // Вычисляет кумулятивную работу PoW для списка блоков как сумму 16^b.difficulty каждого блока.
  public static BigInteger calculateChainWork(List<Block> blocks, int defaultDifficulty) {
    if (blocks == null || blocks.isEmpty()) return BigInteger.ZERO;
    BigInteger total = BigInteger.ZERO;
    for (Block b : blocks) {
      if (b == null) continue;
      int diff = b.difficulty > 0 ? b.difficulty : defaultDifficulty;
      total = total.add(calculateBlockWork(diff));
    }
    return total;
  }

  // Вычисляет награду за блок по алгоритму халвинга (Bitcoin Subsidy Halving).
  public static long calculateBlockSubsidy(long height, long initialReward, long halvingInterval) {
    if (height <= 0 || initialReward <= 0 || halvingInterval <= 0) return 0L;
    long halvings = height / halvingInterval;
    if (halvings >= 64) return 0L;
    return initialReward >> (int) halvings;
  }
}
