package so.fox.nodechain.core;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;

// Единые правила и валидаторы консенсуса сети блокчейна NodeChain (Static-First)
public final class ConsensusUtil {

  private ConsensusUtil() {}

  // Проверяет заголовок блока с явным указанием верхнего лимита времени
  public static boolean isValidBlockHeader(Block prev, Block next, int difficulty, long maxTime) {
    if (next == null || prev == null) return false;
    if (next.height != prev.height + 1) return false;
    if (!prev.hash.equals(next.previousHash)) return false;
    if (next.timestamp < prev.timestamp || next.timestamp > maxTime) return false;
    if (next.height > 0 && !CryptoUtil.isValidAddress(next.minerAddress)) return false;
    if (next.transactions == null || next.txCount != next.transactions.size()) return false;
    if (next.hash == null || !isValidProofOfWork(next.hash, difficulty)) return false;
    if (!next.hash.equals(next.calculateHash())) return false;
    if (next.merkleRoot == null || !next.merkleRoot.equals(next.calculateMerkleRoot())) return false;
    return true;
  }

  // Проверяет соответствие хеша блока заданной сложности PoW
  public static boolean isValidProofOfWork(String hash, int difficulty) {
    if (hash == null) return false;
    String target = "0".repeat(Math.max(1, difficulty));
    return hash.startsWith(target);
  }

  // Проверяет базовую корректность полей и цифровой подписи транзакции
  public static Result<Void> validateTransactionBasics(Transaction tx) {
    if (tx == null || tx.from == null || tx.to == null) return Result.fail(409, "Транзакция отклонена");
    tx.from = tx.from.toLowerCase();
    tx.to = tx.to.toLowerCase();
    if (!CryptoUtil.isValidAddress(tx.from) || !CryptoUtil.isValidAddress(tx.to)) return Result.fail(400, "Некорректный формат адреса");
    if (tx.from.equalsIgnoreCase(tx.to)) return Result.fail(409, "Транзакция отклонена: отправка самому себе");
    if (tx.amount <= 0) return Result.fail(409, "Транзакция отклонена: некорректная сумма");
    if (tx.fee < 0) return Result.fail(409, "Транзакция отклонена: некорректная комиссия");
    if (tx.amount > Long.MAX_VALUE - tx.fee) return Result.fail(409, "Транзакция отклонена: переполнение суммы и комиссии");
    if (tx.comment != null && tx.comment.length() > 256) return Result.fail(409, "Транзакция отклонена: слишком длинный комментарий");
    if (tx.hash == null || !tx.hash.equals(tx.calculateHash())) return Result.fail(409, "Транзакция отклонена: неверный хеш");
    if (!tx.isSignatureValid()) return Result.fail(400, "Подпись не верна");
    return Result.ok(null);
  }

  // Проверяет уникальность хешей транзакций внутри списка одного блока
  public static boolean areTransactionsUnique(List<Transaction> txs) {
    if (txs == null || txs.isEmpty()) return true;
    Set<String> seen = new HashSet<>();
    for (Transaction tx : txs) {
      if (tx == null || tx.hash == null || !seen.add(tx.hash)) return false;
    }
    return true;
  }

  // Вычисляет кумулятивную работу PoW для списка блоков
  public static BigInteger calculateChainWork(List<Block> blocks, int defaultDifficulty) {
    if (blocks == null || blocks.isEmpty()) return BigInteger.ZERO;
    BigInteger totalWork = BigInteger.ZERO;
    for (Block b : blocks) {
      if (b == null) continue;
      int diff = Math.max(1, defaultDifficulty);
      totalWork = totalWork.add(BigInteger.valueOf(16).pow(diff));
    }
    return totalWork;
  }

  // Вычисляет кумулятивную работу PoW для указанного числа блоков и сложности
  public static BigInteger calculateChainWork(int blockCount, int difficulty) {
    if (blockCount <= 0) return BigInteger.ZERO;
    BigInteger singleBlockWork = BigInteger.valueOf(16).pow(Math.max(1, difficulty));
    return singleBlockWork.multiply(BigInteger.valueOf(blockCount));
  }

  /**
   * Вычисляет награду за блок по каноническому алгоритму Bitcoin Core (Subsidy Halving).
   * Каждые halvingInterval блоков базовое вознаграждение делится вдвое через битовый сдвиг (>> halvings).
   * После 64 делений награда становится равной 0.
   */
  public static long calculateBlockSubsidy(long height, long initialReward, long halvingInterval) {
    if (height <= 0 || initialReward <= 0 || halvingInterval <= 0) return 0L;
    long halvings = height / halvingInterval;
    if (halvings >= 64) return 0L;
    return initialReward >> (int) halvings;
  }
}

