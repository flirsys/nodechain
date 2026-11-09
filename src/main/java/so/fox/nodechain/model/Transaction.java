package so.fox.nodechain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.security.PublicKey;
import so.fox.nodechain.crypto.CryptoUtil;

// Модель транзакции блокчейна: отправитель, получатель, сумма, комиссия и цифровая подпись.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Transaction implements Serializable {
  private static final long serialVersionUID = 1L;

  // Формирует детерминированную каноническую строку для подписи транзакции, включая timestamp.
  public static String buildCanonicalData(String chainId, String from, String to, long amount, long fee, long nonce, long timestamp, String comment) {
    String pChain = (chainId != null ? chainId.trim() : "") + "|";
    String pFrom = from != null ? from.toLowerCase() : "";
    String pTo = to != null ? to.toLowerCase() : "";
    String pComment = comment != null ? comment.trim() : "";
    String head = pChain + pFrom + "|" + pTo + "|" + amount + "|" + fee + "|" + nonce + "|" + timestamp;
    return !pComment.isEmpty() ? head + "|" + pComment : head;
  }

  public String hash;
  public Long blockHeight;
  public String blockHash;
  public String chainId;
  public String from;
  public String to;
  public long amount;
  public long fee;
  public long nonce;
  public String publicKey;
  public String signature;
  public String comment;
  public long timestamp = System.currentTimeMillis();
  public String status;
  public int txIndex;

  // Конструктор по умолчанию для десериализации.
  public Transaction() {}

  // Конструктор копирования транзакции для предотвращения мутации объектов в памяти.
  public Transaction(Transaction other) {
    if (other == null) return;
    this.hash = other.hash;
    this.blockHeight = other.blockHeight;
    this.blockHash = other.blockHash;
    this.chainId = other.chainId;
    this.from = other.from;
    this.to = other.to;
    this.amount = other.amount;
    this.fee = other.fee;
    this.nonce = other.nonce;
    this.publicKey = other.publicKey;
    this.signature = other.signature;
    this.comment = other.comment;
    this.timestamp = other.timestamp;
    this.status = other.status;
    this.txIndex = other.txIndex;
  }

  // Конструктор создания новой транзакции с явным указанием timestamp.
  public Transaction(String chainId, String from, String to, long amount, long fee, long nonce, long timestamp, String publicKey, String signature, String comment) {
    this.chainId = chainId != null ? chainId.trim() : "";
    this.from = from != null ? from.trim().toLowerCase() : "";
    this.to = to != null ? to.trim().toLowerCase() : "";
    this.amount = amount;
    this.fee = fee;
    this.nonce = nonce;
    this.timestamp = timestamp;
    this.publicKey = publicKey != null ? publicKey.trim() : null;
    this.signature = signature != null ? signature.trim() : null;
    this.comment = comment != null ? comment.trim() : "";
    this.status = "pending";
    this.hash = calculateHash();
  }

  // Конструктор создания новой транзакции с автоматическим текущим timestamp.
  public Transaction(String chainId, String from, String to, long amount, long fee, long nonce, String publicKey, String signature, String comment) {
    this(chainId, from, to, amount, fee, nonce, System.currentTimeMillis(), publicKey, signature, comment);
  }

  // Вычисляет уникальный SHA-256 хеш транзакции по её содержимому.
  public String calculateHash() {
    String pChain = (chainId != null ? chainId.trim() : "") + "|";
    String pFrom = from != null ? from.toLowerCase().trim() : "";
    String pTo = to != null ? to.toLowerCase().trim() : "";
    String pComment = comment != null ? comment.trim() : "";
    String pSig = signature != null ? signature.trim() : "";
    String payload = pChain + pFrom + "|" + pTo + "|" + amount + "|" + fee + "|" + nonce + "|"
      + timestamp + "|" + pComment + "|" + pSig;
    return CryptoUtil.sha256Hex(payload);
  }

  // Проверяет цифровую подпись транзакции с обязательной валидацией идентификатора сети chainId.
  public boolean isSignatureValid(String expectedChainId) {
    if (publicKey == null || signature == null || from == null) return false;
    if (expectedChainId == null || expectedChainId.isBlank()) return false;
    if (chainId == null || !expectedChainId.equals(chainId)) return false;

    PublicKey pubKey = CryptoUtil.parsePublicKey(publicKey);
    if (pubKey == null) return false;

    String derived = CryptoUtil.getAddressFromPublicKey(pubKey);
    if (derived == null || !derived.equalsIgnoreCase(from)) return false;

    String canonical = buildCanonicalData(chainId, from, to, amount, fee, nonce, timestamp, comment);
    return CryptoUtil.isSignatureValid(pubKey, canonical, signature);
  }
}
