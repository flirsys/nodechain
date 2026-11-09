package so.fox.nodechain;

import java.security.KeyPair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import so.fox.nodechain.core.ConsensusUtil;
import so.fox.nodechain.core.Result;
import so.fox.nodechain.crypto.CryptoUtil;
import so.fox.nodechain.model.Transaction;

// Тесты безопасности транзакций: защита от кражи монет, подделки подписи и мутаций данных.
public class TransactionTheftAndMalleabilitySecurityTest {

  private static final String CHAIN_ID = "foxchain-main-1";

  private static Transaction createSignedTx(KeyPair senderKp, String to, long amount, long fee, long nonce, long ts, String comment) {
    String from = CryptoUtil.getAddressFromPublicKey(senderKp.getPublic());
    String pubKey = CryptoUtil.encodePublicKey(senderKp.getPublic());
    String canonical = Transaction.buildCanonicalData(CHAIN_ID, from, to, amount, fee, nonce, ts, comment);
    String signature = CryptoUtil.sign(senderKp.getPrivate(), canonical);
    return new Transaction(CHAIN_ID, from, to, amount, fee, nonce, ts, pubKey, signature, comment);
  }

  // Проверяет, что валидная подписанная транзакция успешно проходит верификацию.
  @Test
  @DisplayName("Транзакция: легитимная транзакция успешно проходит базовую и криптографическую валидацию")
  public void testValidTransactionPasses() {
    KeyPair sender = CryptoUtil.generateKeyPair();
    KeyPair recipient = CryptoUtil.generateKeyPair();
    String to = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());

    Transaction tx = createSignedTx(sender, to, 1000L, 50L, 0L, System.currentTimeMillis(), "Payment");
    Result<Void> res = ConsensusUtil.validateTransactionBasics(tx, CHAIN_ID);
    Assertions.assertTrue(res.isOk);
    Assertions.assertTrue(tx.isSignatureValid(CHAIN_ID));
  }

  // Проверяет, что подмена получателя или суммы инвалидирует подпись и отклоняется нодой (защита от кражи).
  @Test
  @DisplayName("Кража монет: подмена адреса получателя или суммы транзакции ломает цифровую подпись")
  public void testRecipientAndAmountTamperingFails() {
    KeyPair sender = CryptoUtil.generateKeyPair();
    KeyPair recipient = CryptoUtil.generateKeyPair();
    KeyPair attacker = CryptoUtil.generateKeyPair();

    String to = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());
    String attackerAddr = CryptoUtil.getAddressFromPublicKey(attacker.getPublic());

    Transaction tx = createSignedTx(sender, to, 1000L, 50L, 0L, System.currentTimeMillis(), "Gift");

    // Попытка перенаправить монеты атакующему
    tx.to = attackerAddr;
    Assertions.assertFalse(tx.isSignatureValid(CHAIN_ID));
    Assertions.assertFalse(ConsensusUtil.validateTransactionBasics(tx, CHAIN_ID).isOk);

    // Попытка увеличить сумму перевода
    tx.to = to;
    tx.amount = 9999999L;
    Assertions.assertFalse(tx.isSignatureValid(CHAIN_ID));
    Assertions.assertFalse(ConsensusUtil.validateTransactionBasics(tx, CHAIN_ID).isOk);
  }

  // Проверяет защиту от мутации поля timestamp (защита от сдвига времени и подделки хеша).
  @Test
  @DisplayName("Пластичность: мутация поля timestamp изменяет хеш и инвалидирует цифровую подпись")
  public void testTimestampMalleabilityProtection() {
    KeyPair sender = CryptoUtil.generateKeyPair();
    KeyPair recipient = CryptoUtil.generateKeyPair();
    String to = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());
    long ts = System.currentTimeMillis();

    Transaction tx = createSignedTx(sender, to, 500L, 25L, 1L, ts, "Order#42");
    String originalHash = tx.hash;

    tx.timestamp = ts + 10_000L;
    Assertions.assertNotEquals(originalHash, tx.calculateHash());
    Assertions.assertFalse(tx.isSignatureValid(CHAIN_ID));
    Assertions.assertFalse(ConsensusUtil.validateTransactionBasics(tx, CHAIN_ID).isOk);
  }

  // Проверяет отклонение транзакции при отправке самому себе.
  @Test
  @DisplayName("Логическая валидация: отправка средств самому себе запрещена")
  public void testSelfTransferRejected() {
    KeyPair sender = CryptoUtil.generateKeyPair();
    String from = CryptoUtil.getAddressFromPublicKey(sender.getPublic());

    Transaction tx = createSignedTx(sender, from, 100L, 10L, 0L, System.currentTimeMillis(), "Self");
    Result<Void> res = ConsensusUtil.validateTransactionBasics(tx, CHAIN_ID);
    Assertions.assertFalse(res.isOk);
    Assertions.assertEquals(409, res.status);
  }

  // Проверяет отклонение отрицательных сумм, нулевых сумм и переполнения Long.MAX_VALUE.
  @Test
  @DisplayName("Математическая безопасность: отклонение отрицательных сумм и переполнения Long")
  public void testInvalidAmountsAndOverflowRejected() {
    KeyPair sender = CryptoUtil.generateKeyPair();
    KeyPair recipient = CryptoUtil.generateKeyPair();
    String to = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());

    Transaction txZero = createSignedTx(sender, to, 0L, 10L, 0L, System.currentTimeMillis(), "");
    Assertions.assertFalse(ConsensusUtil.validateTransactionBasics(txZero, CHAIN_ID).isOk);

    Transaction txNeg = createSignedTx(sender, to, -100L, 10L, 0L, System.currentTimeMillis(), "");
    Assertions.assertFalse(ConsensusUtil.validateTransactionBasics(txNeg, CHAIN_ID).isOk);

    Transaction txOverflow = createSignedTx(sender, to, Long.MAX_VALUE, 50L, 0L, System.currentTimeMillis(), "");
    Assertions.assertFalse(ConsensusUtil.validateTransactionBasics(txOverflow, CHAIN_ID).isOk);
  }

  // Проверяет защиту от Replay-атак на другой блокчейн сети (несовпадение chainId).
  @Test
  @DisplayName("Replay Attack: отклонение транзакции с чужим chainId")
  public void testCrossChainReplayRejected() {
    KeyPair sender = CryptoUtil.generateKeyPair();
    KeyPair recipient = CryptoUtil.generateKeyPair();
    String to = CryptoUtil.getAddressFromPublicKey(recipient.getPublic());

    Transaction tx = createSignedTx(sender, to, 100L, 10L, 0L, System.currentTimeMillis(), "");
    Assertions.assertFalse(ConsensusUtil.validateTransactionBasics(tx, "other-chain-id").isOk);
    Assertions.assertFalse(tx.isSignatureValid("other-chain-id"));
  }
}

