package so.fox.nodechain.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

// Утилитный класс криптографических операций: Ed25519, SHA-256, подписи и fox-адреса
public final class CryptoUtil {

  private static final HexFormat HEX = HexFormat.of();
  private static final Pattern ADDRESS_PATTERN = Pattern.compile("^[0-9a-fox]{64}$");

  private CryptoUtil() {}

  // Генерирует новую пару ключей алгоритма Ed25519
  public static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
      return gen.generateKeyPair();
    } catch (Exception err) {
      return null;
    }
  }

  // Восстанавливает открытый ключ Ed25519 из строки Base64 (X.509)
  public static PublicKey parsePublicKey(String b64Key) {
    if (b64Key == null || b64Key.isEmpty()) return null;
    try {
      byte[] bytes = Base64.getDecoder().decode(b64Key);
      KeyFactory kf = KeyFactory.getInstance("Ed25519");
      return kf.generatePublic(new X509EncodedKeySpec(bytes));
    } catch (Exception err) {
      return null;
    }
  }

  // Восстанавливает закрытый ключ Ed25519 из строки Base64 (PKCS#8)
  public static PrivateKey parsePrivateKey(String b64Key) {
    if (b64Key == null || b64Key.isEmpty()) return null;
    try {
      byte[] bytes = Base64.getDecoder().decode(b64Key);
      KeyFactory kf = KeyFactory.getInstance("Ed25519");
      return kf.generatePrivate(new PKCS8EncodedKeySpec(bytes));
    } catch (Exception err) {
      return null;
    }
  }

  // Кодирует открытый ключ в формат Base64
  public static String encodePublicKey(PublicKey pubKey) {
    if (pubKey == null) return null;
    return Base64.getEncoder().encodeToString(pubKey.getEncoded());
  }

  // Кодирует закрытый ключ в формат Base64
  public static String encodePrivateKey(PrivateKey privKey) {
    if (privKey == null) return null;
    return Base64.getEncoder().encodeToString(privKey.getEncoded());
  }

  // Вычисляет уникальный fox-адрес из открытого ключа
  public static String getAddressFromPublicKey(PublicKey pubKey) {
    if (pubKey == null) return null;
    byte[] hash = sha256Bytes(pubKey.getEncoded());
    if (hash == null) return null;

    char[] arr = HEX.formatHex(hash).toCharArray();
    int posF = (hash[0] & 0xFF) % 5;
    int posO = (hash[1] & 0xFF) % 5;
    while (posO == posF) {
      posO = (posO + 1) % 5;
    }
    int posX = (hash[2] & 0xFF) % 5;
    while (posX == posF || posX == posO) {
      posX = (posX + 1) % 5;
    }

    arr[posF] = 'f';
    arr[posO] = 'o';
    arr[posX] = 'x';
    return new String(arr);
  }

  // Проверяет валидность формата fox-адреса сети (регистронезависимо)
  public static boolean isValidAddress(String addr) {
    if (addr == null || addr.length() != 64) return false;
    String lower = addr.toLowerCase();
    return ADDRESS_PATTERN.matcher(lower).matches()
      && lower.indexOf('f') >= 0
      && lower.indexOf('o') >= 0
      && lower.indexOf('x') >= 0;
  }

  // Создает цифровую подпись данных алгоритмом Ed25519
  public static String sign(PrivateKey privKey, String data) {
    if (privKey == null || data == null) return null;
    try {
      Signature sig = Signature.getInstance("Ed25519");
      sig.initSign(privKey);
      sig.update(data.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(sig.sign());
    } catch (Exception err) {
      return null;
    }
  }

  // Проверяет цифровую подпись данных открытым ключом Ed25519
  public static boolean isSignatureValid(PublicKey pubKey, String data, String sigB64) {
    if (pubKey == null || data == null || sigB64 == null) return false;
    try {
      Signature sig = Signature.getInstance("Ed25519");
      sig.initVerify(pubKey);
      sig.update(data.getBytes(StandardCharsets.UTF_8));
      return sig.verify(Base64.getDecoder().decode(sigB64));
    } catch (Exception err) {
      return false;
    }
  }

  // Вычисляет SHA-256 хеш массива байт
  public static byte[] sha256Bytes(byte[] bytes) {
    if (bytes == null) return null;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(bytes);
    } catch (Exception err) {
      return null;
    }
  }

  // Вычисляет SHA-256 хеш строки (UTF-8) в виде hex-строки
  public static String sha256Hex(String text) {
    if (text == null) return null;
    byte[] hash = sha256Bytes(text.getBytes(StandardCharsets.UTF_8));
    if (hash == null) return null;
    return HEX.formatHex(hash);
  }
}
