package so.fox.nodechain.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;

// Единые правила и валидаторы консенсуса сети блокчейна NodeChain (Static-First)
public final class ConsensusUtil {

  private ConsensusUtil() {}

  public static final long MIN_RELAY_FEE = 1L;

  // Вычисляет медианное время прошлых блоков (Median-Time-Past MTP-11)
  public static long calculateMedianTimePast(List<Long> timestamps) {
    if (timestamps == null || timestamps.isEmpty()) return 0L;
    List<Long> sorted = new ArrayList<>(timestamps);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }

  // Проверяет заголовок блока с валидацией PoW, MTP, лимита времени и chainId
  public static boolean isValidBlockHeader(Block prev, Block next, int difficulty, long maxTime, String expectedChainId, long mtp) {
    if (next == null || prev == null) return false;
    if (next.height != prev.height + 1) return false;
    if (!prev.hash.equals(next.previousHash)) return false;
    if (next.timestamp <= mtp || next.timestamp > maxTime) return false;
    if (expectedChainId != null && !expectedChainId.isBlank()) {
      if (next.chainId == null || !expectedChainId.equals(next.chainId)) return false;
    }
    if (prev.chainId != null && next.chainId != null && !prev.chainId.equals(next.chainId)) return false;
    if (next.height > 0 && !CryptoUtil.isValidAddress(next.minerAddress)) return false;
    if (next.transactions == null || next.txCount != next.transactions.size()) return false;
    if (next.difficulty != difficulty) return false;
    if (next.hash == null || !isValidProofOfWork(next.hash, difficulty)) return false;
    if (!next.hash.equals(next.calculateHash())) return false;
    if (next.merkleRoot == null || !next.merkleRoot.equals(next.calculateMerkleRoot())) return false;
    return true;
  }

  // Вычисляет ожидаемую сложность следующего блока (Bitcoin-style Difficulty Adjustment)
  public static int calculateNextDifficulty(Block prev, Block intervalAnchor, so.fox.nodechain.config.NodeConfig config) {
    if (prev == null || prev.height == 0) return config.difficulty;
    int interval = config.difficultyAdjustmentInterval;
    long nextHeight = prev.height + 1;
    if (interval <= 0 || nextHeight % interval != 0) {
      return prev.difficulty > 0 ? prev.difficulty : config.difficulty;
    }
    if (intervalAnchor == null) return prev.difficulty > 0 ? prev.difficulty : config.difficulty;

    long targetTimeSpan = (long) interval * config.targetBlockTimeSeconds * 1000L;
    long anchorTime = (intervalAnchor.timestamp > 0) ? intervalAnchor.timestamp : (prev.timestamp - targetTimeSpan);
    long actualTimeSpan = Math.max(1L, prev.timestamp - anchorTime);

    int currentDiff = prev.difficulty > 0 ? prev.difficulty : config.difficulty;
    if (actualTimeSpan < targetTimeSpan / 2) {
      return Math.min(config.maxDifficulty, currentDiff + 1);
    }
    if (actualTimeSpan > targetTimeSpan * 2) {
      return Math.max(config.minDifficulty, currentDiff - 1);
    }
    return currentDiff;
  }

  // Проверяет соответствие хеша блока заданной сложности PoW
  public static boolean isValidProofOfWork(String hash, int difficulty) {
    if (hash == null) return false;
    String target = "0".repeat(Math.max(1, difficulty));
    return hash.startsWith(target);
  }

  // Проверяет формат и хеш транзакции без тяжелой криптографической валидации подписи
  public static Result<Void> validateTransactionFormat(Transaction tx) {
    if (tx == null || tx.from == null || tx.to == null) return Result.fail(409, "Транзакция отклонена");
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

  // Проверяет базовую корректность полей и цифровой подписи транзакции строго с учетом chainId
  public static Result<Void> validateTransactionBasics(Transaction tx, String expectedChainId) {
    Result<Void> formatRes = validateTransactionFormat(tx);
    if (formatRes.isError()) return formatRes;
    if (expectedChainId == null || expectedChainId.isBlank() || tx.chainId == null || !expectedChainId.equals(tx.chainId)) {
      return Result.fail(409, "Транзакция отклонена: неверный chainId");
    }
    if (!tx.isSignatureValid(expectedChainId)) return Result.fail(400, "Подпись не верна");
    return Result.ok(null);
  }

  // Проверяет уникальность хешей транзакций внутри списка одного блока
  public static boolean isTransactionsUnique(List<Transaction> txs) {
    if (txs == null || txs.isEmpty()) return true;
    Set<String> seen = new HashSet<>();
    for (Transaction tx : txs) {
      if (tx == null || tx.hash == null || !seen.add(tx.hash)) return false;
    }
    return true;
  }

  // Вычисляет PoW-работу для одного блока: 16^difficulty
  public static BigInteger calculateBlockWork(int difficulty) {
    if (difficulty <= 0) return BigInteger.ONE;
    return BigInteger.valueOf(16).pow(difficulty);
  }

  // Вычисляет кумулятивную работу PoW для списка блоков как сумму 16^b.difficulty каждого блока
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

  // Вычисляет награду за блок по алгоритму халвинга (Bitcoin Subsidy Halving)
  public static long calculateBlockSubsidy(long height, long initialReward, long halvingInterval) {
    if (height <= 0 || initialReward <= 0 || halvingInterval <= 0) return 0L;
    long halvings = height / halvingInterval;
    if (halvings >= 64) return 0L;
    return initialReward >> (int) halvings;
  }
}
