package so.fox.nodechain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.fox.nodechain.core.NodeWalletService;
import so.fox.nodechain.crypto.CryptoUtil;

// Комплексные тесты криптографии и безопасности хранения ключей кошелька ноды.
public class CryptoAndWalletSecurityTest {

  @TempDir
  Path tempDir;

  // Проверяет генерацию, экспорт и импорт ключей Ed25519 со строгой верификацией соответствия.
  @Test
  @DisplayName("Ed25519: генерация, импорт, экспорт и валидность цифровой подписи")
  public void testEd25519KeysAndSignatures() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    Assertions.assertNotNull(kp);

    String privB64 = CryptoUtil.encodePrivateKey(kp.getPrivate());
    String pubB64 = CryptoUtil.encodePublicKey(kp.getPublic());
    Assertions.assertNotNull(privB64);
    Assertions.assertNotNull(pubB64);

    PrivateKey parsedPriv = CryptoUtil.parsePrivateKey(privB64);
    PublicKey parsedPub = CryptoUtil.parsePublicKey(pubB64);
    Assertions.assertNotNull(parsedPriv);
    Assertions.assertNotNull(parsedPub);

    String payload = "FoxNodeChain-Payload-2026";
    String sig = CryptoUtil.sign(parsedPriv, payload);
    Assertions.assertNotNull(sig);
    Assertions.assertTrue(CryptoUtil.isSignatureValid(parsedPub, payload, sig));
    Assertions.assertFalse(CryptoUtil.isSignatureValid(parsedPub, payload + "!", sig));
  }

  // Проверяет защиту парсера ключей от неканонического Base64 и битых данных.
  @Test
  @DisplayName("Ed25519: отсечение неканонического Base64 и некорректных байт ключей")
  public void testRejectsNonCanonicalBase64Keys() {
    Assertions.assertNull(CryptoUtil.parsePrivateKey(null));
    Assertions.assertNull(CryptoUtil.parsePrivateKey(""));
    Assertions.assertNull(CryptoUtil.parsePrivateKey("not-a-valid-base64!"));
    Assertions.assertNull(CryptoUtil.parsePublicKey(null));
    Assertions.assertNull(CryptoUtil.parsePublicKey(""));
    Assertions.assertNull(CryptoUtil.parsePublicKey("not-a-valid-base64!"));

    KeyPair kp = CryptoUtil.generateKeyPair();
    String pubB64 = CryptoUtil.encodePublicKey(kp.getPublic());
    Assertions.assertNull(CryptoUtil.parsePublicKey(pubB64 + "=="));
  }

  // Проверяет формат Fox-адресов, префиксы f,o,x, регистронезависимость и CRC32-контрольную сумму.
  @Test
  @DisplayName("Fox-адрес: строгий формат, символы fox в префиксе и верификация CRC32")
  public void testFoxAddressGenerationAndValidation() {
    KeyPair kp = CryptoUtil.generateKeyPair();
    String addr = CryptoUtil.getAddressFromPublicKey(kp.getPublic());
    Assertions.assertNotNull(addr);
    Assertions.assertEquals(64, addr.length());
    Assertions.assertTrue(CryptoUtil.isValidAddress(addr));

    String prefix = addr.substring(0, 5);
    Assertions.assertTrue(prefix.indexOf('f') >= 0);
    Assertions.assertTrue(prefix.indexOf('o') >= 0);
    Assertions.assertTrue(prefix.indexOf('x') >= 0);

    Assertions.assertTrue(CryptoUtil.isValidAddress(addr.toUpperCase()));

    char[] corrupted = addr.toCharArray();
    corrupted[10] = (corrupted[10] == 'a') ? 'b' : 'a';
    Assertions.assertFalse(CryptoUtil.isValidAddress(new String(corrupted)));
  }

  // Проверяет генерацию и безопасное сохранение файла кошелька nodewallet.dat.
  @Test
  @DisplayName("NodeWalletService: генерация и целостность файла nodewallet.dat")
  public void testNodeWalletServiceGenerationAndLoading() throws IOException {
    Path walletPath = tempDir.resolve("nodewallet.dat");
    NodeWalletService walletService = new NodeWalletService(walletPath);
    walletService.init();

    Assertions.assertNotNull(walletService.address);
    Assertions.assertTrue(CryptoUtil.isValidAddress(walletService.address));
    Assertions.assertNotNull(walletService.privateKey);
    Assertions.assertNotNull(walletService.publicKey);
    Assertions.assertTrue(Files.exists(walletPath));

    NodeWalletService reloadedService = new NodeWalletService(walletPath);
    reloadedService.init();
    Assertions.assertEquals(walletService.address, reloadedService.address);
  }

  // Проверяет защиту от загрузки поврежденного или поддельного файла кошелька.
  @Test
  @DisplayName("NodeWalletService: отклонение поврежденного файла кошелька с несовпадающим ключом")
  public void testNodeWalletServiceCorruptedFileRejection() throws IOException {
    Path walletPath = tempDir.resolve("nodewallet_corrupt.dat");
    KeyPair kp1 = CryptoUtil.generateKeyPair();
    KeyPair kp2 = CryptoUtil.generateKeyPair();

    String content = CryptoUtil.encodePrivateKey(kp1.getPrivate()) + System.lineSeparator()
      + CryptoUtil.encodePublicKey(kp2.getPublic()) + System.lineSeparator()
      + CryptoUtil.getAddressFromPublicKey(kp1.getPublic()) + System.lineSeparator();
    Files.writeString(walletPath, content);

    NodeWalletService walletService = new NodeWalletService(walletPath);
    Assertions.assertThrows(IllegalStateException.class, walletService::init);
  }
}

