package so.fox.nodechain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.ProtocolException;
import java.security.KeyPair;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import so.fox.nodechain.config.NodeConfig;
import so.fox.nodechain.core.ConsensusUtil;
import so.fox.nodechain.core.Result;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Block;
import so.fox.nodechain.model.Transaction;
import so.fox.nodechain.p2p.packet.NodePacket;
import so.fox.nodechain.p2p.packet.OpCode;

/**
 * Тесты новых механизмов безопасности: DAA, защита от Cross-Chain Replay,
 * каскадный сброс mempool при Nonce Gap и строгие лимиты фреймов пакетов.
 */
public class NewPlanSecurityAndDaaTest {

  /**
   * Проверяет увеличение сложности при слишком быстром майнинге интервала (Bitcoin DAA).
   */
  @Test
  @DisplayName("DAA: Увеличение сложности при опережении целевого времени блока")
  public void testDaaAdjustmentIncreaseOnFastBlocks() {
    NodeConfig cfg = new NodeConfig();
    cfg.difficulty = 4;
    cfg.difficultyAdjustmentInterval = 10;
    cfg.targetBlockTimeSeconds = 10;
    cfg.minDifficulty = 1;
    cfg.maxDifficulty = 16;

    long baseTime = 1_000_000_000L;
    Block anchor = new Block(0, "0", "miner", Collections.emptyList(), 4);
    anchor.timestamp = baseTime;

    // 10 блоков сгенерированы за 20 секунд вместо целевых 100 секунд (< target / 2)
    Block latest = new Block(9, "prev", "miner", Collections.emptyList(), 4);
    latest.timestamp = baseTime + 20_000L;

    int nextDiff = ConsensusUtil.calculateNextDifficulty(latest, anchor, cfg);
    Assertions.assertEquals(5, nextDiff, "Сложность должна вырасти с 4 до 5 при быстром майнинге");
  }

  /**
   * Проверяет уменьшение сложности при слишком медленном майнинге интервала.
   */
  @Test
  @DisplayName("DAA: Уменьшение сложности при задержке целевого времени блока")
  public void testDaaAdjustmentDecreaseOnSlowBlocks() {
    NodeConfig cfg = new NodeConfig();
    cfg.difficulty = 4;
    cfg.difficultyAdjustmentInterval = 10;
    cfg.targetBlockTimeSeconds = 10;
    cfg.minDifficulty = 1;
    cfg.maxDifficulty = 16;

    long baseTime = 1_000_000_000L;
    Block anchor = new Block(0, "0", "miner", Collections.emptyList(), 4);
    anchor.timestamp = baseTime;

    // 10 блоков сгенерированы за 300 секунд вместо целевых 100 секунд (> target * 2)
    Block latest = new Block(9, "prev", "miner", Collections.emptyList(), 4);
    latest.timestamp = baseTime + 300_000L;

    int nextDiff = ConsensusUtil.calculateNextDifficulty(latest, anchor, cfg);
    Assertions.assertEquals(3, nextDiff, "Сложность должна снизиться с 4 до 3 при медленном майнинге");
  }

  /**
   * Проверяет соблюдение границ minDifficulty и maxDifficulty алгоритмом DAA.
   */
  @Test
  @DisplayName("DAA: Ограничение диапазона минимальной и максимальной сложности")
  public void testDaaClampedBounds() {
    NodeConfig cfg = new NodeConfig();
    cfg.difficultyAdjustmentInterval = 10;
    cfg.targetBlockTimeSeconds = 10;
    cfg.minDifficulty = 2;
    cfg.maxDifficulty = 8;

    long baseTime = 1_000_000_000L;
    Block anchor = new Block(0, "0", "miner", Collections.emptyList(), 2);
    anchor.timestamp = baseTime;

    // Медленный майнинг на границе minDifficulty не должен опускать сложность ниже minDifficulty (2)
    Block latestMin = new Block(9, "prev", "miner", Collections.emptyList(), 2);
    latestMin.timestamp = baseTime + 500_000L;
    int clampedMin = ConsensusUtil.calculateNextDifficulty(latestMin, anchor, cfg);
    Assertions.assertEquals(2, clampedMin);

    // Быстрый майнинг на границе maxDifficulty не должен поднимать сложность выше maxDifficulty (8)
    anchor.difficulty = 8;
    Block latestMax = new Block(9, "prev", "miner", Collections.emptyList(), 8);
    latestMax.timestamp = baseTime + 5_000L;
    int clampedMax = ConsensusUtil.calculateNextDifficulty(latestMax, anchor, cfg);
    Assertions.assertEquals(8, clampedMax);
  }

  /**
   * Проверяет защиту от атаки повторного воспроизведения между сетями (Cross-Chain Replay).
   */
  @Test
  @DisplayName("ChainId: Защита от межсетевого воспроизведения транзакций (Testnet -> Mainnet)")
  public void testCrossChainReplayProtection() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String sender = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    KeyPair recKp = CryptoUtil.generateKeyPair();
    String recipient = CryptoUtil.getAddressFromPublicKey(recKp.getPublic());

    String testnetChain = "foxchain-test-1";
    String mainnetChain = "foxchain-main-1";

    // Подписываем транзакцию в контексте тестнета
    String canonicalTestnet = Transaction.buildCanonicalData(testnetChain, sender, recipient, 1000L, 50L, 0L, "Payment");
    String sig = CryptoUtil.sign(kp.getPrivate(), canonicalTestnet);

    Transaction tx = new Transaction(
      testnetChain, sender, recipient, 1000L, 50L, 0L,
      CryptoUtil.encodePublicKey(kp.getPublic()), sig, "Payment"
    );

    // Валидация в тестнете успешна
    Result<Void> testnetRes = ConsensusUtil.validateTransactionBasics(tx, testnetChain);
    Assertions.assertFalse(testnetRes.isError());

    // Попытка отправить ту же транзакцию в майннет отклоняется с ошибкой chainId
    Result<Void> mainnetRes = ConsensusUtil.validateTransactionBasics(tx, mainnetChain);
    Assertions.assertTrue(mainnetRes.isError());
    Assertions.assertEquals(409, mainnetRes.status);
    Assertions.assertEquals("Транзакция отклонена: неверный chainId", mainnetRes.error);
  }

  /**
   * Проверяет отсечение пакетов PING, превышающих максимальный лимит фрейма 16 байт (Heap OOM защита).
   */
  @Test
  @DisplayName("NodePacket: Отсечение оверсайз-пакетов по опкодам (Heap OOM DoS)")
  public void testNodePacketOpCodeFrameLimitsPing() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(baos);
    try {
      dos.writeShort(NodePacket.MAGIC);
      dos.writeByte(NodePacket.FLAG_PLAIN);
      dos.writeInt(1); // requestId
      dos.writeByte(OpCode.PING); // opCode
      dos.writeInt(1024); // 1024 байта для PING при лимите 16 байт
      dos.write(new byte[1024]);
      dos.flush();
    } catch (Exception err) {
      Assertions.fail(err);
    }

    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    Assertions.assertThrows(java.io.IOException.class, () -> NodePacket.readFrom(bais));
  }

  /**
   * Проверяет расчет работы цепи как точной суммы 16^b.difficulty (Bitcoin-style Chain Work).
   */
  @Test
  @DisplayName("Consensus: Суммирование работы цепи по формуле 16^diff каждого блока")
  public void testExactChainWorkSum() {
    Block b1 = new Block(1, "0", "m1", Collections.emptyList(), 2);
    Block b2 = new Block(2, "prev", "m2", Collections.emptyList(), 4);

    // b1.work = 16^2 = 256; b2.work = 16^4 = 65536; total = 65792
    java.math.BigInteger totalWork = ConsensusUtil.calculateChainWork(java.util.List.of(b1, b2), 4);
    Assertions.assertEquals(java.math.BigInteger.valueOf(65792L), totalWork);
  }

  /**
   * Проверяет, что нода никогда не поверит злоумышленнику, приславшему блок с заниженной сложностью (0 или 1).
   */
  @Test
  @DisplayName("Consensus: Отклонение блока с заниженной или поддельной сложностью")
  public void testAttackerFakedDifficultyBlockRejected() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String miner = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    Block genesis = Block.createGenesis(miner, 4);
    long maxTime = System.currentTimeMillis() + 600_000L;

    // 1. Злоумышленник майнит блок со сложностью 1 вместо требуемых сетью 4
    Block badDiffBlock = new Block(1, genesis.hash, miner, Collections.emptyList(), 1);
    badDiffBlock.mineBlock(1);
    Assertions.assertTrue(badDiffBlock.hash.startsWith("0"));

    // Нода требует сложность 4 (expectedDiff = 4). Блок должен быть отклонен!
    boolean isValid = ConsensusUtil.isValidBlockHeader(genesis, badDiffBlock, 4, maxTime);
    Assertions.assertFalse(isValid, "Блок со сложностью 1 обязан быть отклонен, если консенсус требует 4");

    // 2. Злоумышленник майнит блок со сложностью 0
    Block zeroDiffBlock = new Block(1, genesis.hash, miner, Collections.emptyList(), 0);
    zeroDiffBlock.mineBlock(0);
    boolean isZeroValid = ConsensusUtil.isValidBlockHeader(genesis, zeroDiffBlock, 4, maxTime);
    Assertions.assertFalse(isZeroValid, "Блок со сложностью 0 обязан быть отклонен");

    // 3. Злоумышленник указал в поле difficulty = 4, но сгенерировал хеш только с 1 нулем
    badDiffBlock.difficulty = 4;
    boolean isSpoofedValid = ConsensusUtil.isValidBlockHeader(genesis, badDiffBlock, 4, maxTime);
    Assertions.assertFalse(isSpoofedValid, "Хеш с 1 нулем не может удовлетворить проверку сложности 4");
  }

  /**
   * Проверяет отклонение транзакций с отрицательным nonce или недопустимым timestamp (защита от отравления истории).
   */
  @Test
  @DisplayName("Consensus: Отклонение транзакций с nonce < 0 и некорректным timestamp")
  public void testTransactionFormatRejectsNegativeNonceAndInvalidTimestamp() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String from = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    KeyPair kp2 = CryptoUtil.generateKeyPair();
    String to = CryptoUtil.getAddressFromPublicKey(kp2.getPublic());

    Transaction tx = new Transaction("foxchain-main-1", from, to, 100L, 10L, 0L, CryptoUtil.encodePublicKey(kp.getPublic()), "sig", "");
    Assertions.assertFalse(ConsensusUtil.validateTransactionFormat(tx).isError());

    // 1. Отрицательный nonce
    tx.nonce = -1L;
    tx.hash = tx.calculateHash();
    Result<Void> nonceRes = ConsensusUtil.validateTransactionFormat(tx);
    Assertions.assertTrue(nonceRes.isError());
    Assertions.assertEquals(409, nonceRes.status);
    Assertions.assertTrue(nonceRes.error.contains("nonce"));

    // 2. Timestamp = 0
    tx.nonce = 0L;
    tx.timestamp = 0L;
    tx.hash = tx.calculateHash();
    Result<Void> timeZeroRes = ConsensusUtil.validateTransactionFormat(tx);
    Assertions.assertTrue(timeZeroRes.isError());
    Assertions.assertEquals(409, timeZeroRes.status);
    Assertions.assertTrue(timeZeroRes.error.contains("время"));

    // 3. Timestamp из далекого будущего (> +600s)
    tx.timestamp = System.currentTimeMillis() + 700_000L;
    tx.hash = tx.calculateHash();
    Result<Void> futureTimeRes = ConsensusUtil.validateTransactionFormat(tx);
    Assertions.assertTrue(futureTimeRes.isError());
    Assertions.assertEquals(409, futureTimeRes.status);
    Assertions.assertTrue(futureTimeRes.error.contains("время"));
  }
}
