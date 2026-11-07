package so.fox.nodechain;

import java.security.KeyPair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import so.fox.nodechain.crypto.CryptoUtil;

/**
 * Модульные тесты криптографических утилит: ключи, fox-адреса и подписи.
 */
public class CryptoUtilTest {

  /**
   * Проверяет генерацию и формат валидного fox-адреса.
   */
  @Test
  public void testAddressGenerationAndValidation() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String addr = CryptoUtil.getAddressFromPublicKey(kp.getPublic());

    Assertions.assertNotNull(addr);
    Assertions.assertEquals(64, addr.length());
    Assertions.assertTrue(CryptoUtil.isValidAddress(addr));
    Assertions.assertTrue(CryptoUtil.isValidAddress(addr.toUpperCase()));
    Assertions.assertTrue(addr.contains("f"));
    Assertions.assertTrue(addr.contains("o"));
    Assertions.assertTrue(addr.contains("x"));

    // Проверяем устойчивость контрольной суммы: опечатка в любом символе инвалидирует адрес
    char[] corrupted = addr.toCharArray();
    corrupted[10] = (corrupted[10] == 'a') ? 'b' : 'a';
    Assertions.assertFalse(CryptoUtil.isValidAddress(new String(corrupted)), "Опечатка в теле адреса должна отклоняться контрольной суммой");

    char[] corruptedChecksum = addr.toCharArray();
    corruptedChecksum[60] = (corruptedChecksum[60] == '0') ? '1' : '0';
    Assertions.assertFalse(CryptoUtil.isValidAddress(new String(corruptedChecksum)), "Искажение контрольной суммы должно отклоняться");
  }

  /**
   * Проверяет подписание и верификацию цифровой подписи Ed25519.
   */
  @Test
  public void testSignAndVerify() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String data = "fox_test_data_123";
    String sig = CryptoUtil.sign(kp.getPrivate(), data);

    Assertions.assertNotNull(sig);
    Assertions.assertTrue(CryptoUtil.isSignatureValid(kp.getPublic(), data, sig));
    Assertions.assertFalse(CryptoUtil.isSignatureValid(kp.getPublic(), "tampered_data", sig));
  }
}
