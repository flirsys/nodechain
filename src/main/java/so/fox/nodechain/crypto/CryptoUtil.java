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
import java.util.zip.CRC32;

// Утилитный класс криптографических операций: Ed25519, SHA-256, подписи и fox-адреса.
public final class CryptoUtil {

  private static final HexFormat HEX = HexFormat.of();
  private static final Pattern ADDRESS_PREFIX_PATTERN = Pattern.compile("^[0-9a-fox]{5}$");
  private static final Pattern ADDRESS_SUFFIX_PATTERN = Pattern.compile("^[0-9a-f]{59}$");

  private CryptoUtil() {}

  // Генерирует новую пару ключей алгоритма Ed25519.
  public static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
      return gen.generateKeyPair();
    } catch (Exception err) {
      return null;
    }
  }

  // Восстанавливает открытый ключ Ed25519 из строки Base64 (X.509).
  public static PublicKey parsePublicKey(String b64Key) {
    if (b64Key == null || b64Key.isEmpty()) return null;
    try {
      byte[] bytes = Base64.getDecoder().decode(b64Key);
      if (!b64Key.equals(Base64.getEncoder().encodeToString(bytes))) return null;
      KeyFactory kf = KeyFactory.getInstance("Ed25519");
      return kf.generatePublic(new X509EncodedKeySpec(bytes));
    } catch (Exception err) {
      return null;
    }
  }

  // Восстанавливает закрытый ключ Ed25519 из строки Base64 (PKCS#8) с проверкой каноничности.
  public static PrivateKey parsePrivateKey(String b64Key) {
    if (b64Key == null || b64Key.isEmpty()) return null;
    try {
      byte[] bytes = Base64.getDecoder().decode(b64Key);
      if (!b64Key.equals(Base64.getEncoder().encodeToString(bytes))) return null;
      KeyFactory kf = KeyFactory.getInstance("Ed25519");
      return kf.generatePrivate(new PKCS8EncodedKeySpec(bytes));
    } catch (Exception err) {
      return null;
    }
  }

  // Кодирует открытый ключ в формат Base64.
  public static String encodePublicKey(PublicKey pubKey) {
    if (pubKey == null) return null;
    return Base64.getEncoder().encodeToString(pubKey.getEncoded());
  }

  // Кодирует закрытый ключ в формат Base64.
  public static String encodePrivateKey(PrivateKey privKey) {
    if (privKey == null) return null;
    return Base64.getEncoder().encodeToString(privKey.getEncoded());
  }

  // Вычисляет уникальный fox-адрес из открытого ключа со встроенной контрольной суммой CRC32.
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

    String payload = new String(arr, 0, 56);
    String checksum = computeChecksumHex(payload);
    System.arraycopy(checksum.toCharArray(), 0, arr, 56, 8);

    return new String(arr);
  }

  // Проверяет валидность формата fox-адреса сети и контрольную сумму (регистронезависимо).
  public static boolean isValidAddress(String addr) {
    if (addr == null || addr.length() != 64) return false;
    String lower = addr.toLowerCase();
    String prefix = lower.substring(0, 5);
    if (!ADDRESS_PREFIX_PATTERN.matcher(prefix).matches()) return false;
    if (prefix.indexOf('f') < 0 || prefix.indexOf('o') < 0 || prefix.indexOf('x') < 0) return false;

    String suffix = lower.substring(5);
    if (!ADDRESS_SUFFIX_PATTERN.matcher(suffix).matches()) return false;

    String payload = lower.substring(0, 56);
    String expected = computeChecksumHex(payload);
    return lower.substring(56).equalsIgnoreCase(expected);
  }

  // Вычисляет 8-символьную hex контрольную сумму CRC32.
  public static String computeChecksumHex(String payload) {
    if (payload == null) return "";
    CRC32 crc = new CRC32();
    crc.update(payload.getBytes(StandardCharsets.UTF_8));
    return String.format("%08x", crc.getValue());
  }

  // Создает цифровую подпись данных алгоритмом Ed25519.
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

  // Проверяет цифровую подпись данных открытым ключом Ed25519.
  public static boolean isSignatureValid(PublicKey pubKey, String data, String sigB64) {
    if (pubKey == null || data == null || sigB64 == null) return false;
    try {
      byte[] sigBytes = Base64.getDecoder().decode(sigB64);
      if (!sigB64.equals(Base64.getEncoder().encodeToString(sigBytes))) return false;
      Signature sig = Signature.getInstance("Ed25519");
      sig.initVerify(pubKey);
      sig.update(data.getBytes(StandardCharsets.UTF_8));
      return sig.verify(sigBytes);
    } catch (Exception err) {
      return false;
    }
  }

  // Вычисляет SHA-256 хеш массива байт.
  public static byte[] sha256Bytes(byte[] bytes) {
    if (bytes == null) return null;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(bytes);
    } catch (Exception err) {
      return null;
    }
  }

  // Вычисляет SHA-256 хеш строки (UTF-8) в виде hex-строки.
  public static String sha256Hex(String text) {
    if (text == null) return null;
    byte[] hash = sha256Bytes(text.getBytes(StandardCharsets.UTF_8));
    if (hash == null) return null;
    return HEX.formatHex(hash);
  }
}
