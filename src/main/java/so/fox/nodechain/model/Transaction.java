package so.fox.nodechain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.security.PublicKey;
import so.fox.nodechain.crypto.CryptoUtil;

// Модель транзакции блокчейна: отправитель, получатель, сумма, комиссия и цифровая подпись
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Transaction implements Serializable {
  private static final long serialVersionUID = 1L;

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
  public long timestamp;
  public String status;
  public int txIndex;

  public Transaction() {}

  // Конструктор создания новой транзакции с обязательным указанием сети chainId
  public Transaction(String chainId, String from, String to, long amount, long fee, long nonce, String publicKey, String signature, String comment) {
    this.chainId = chainId != null ? chainId.trim() : "";
    this.from = from != null ? from.toLowerCase() : "";
    this.to = to != null ? to.toLowerCase() : "";
    this.amount = amount;
    this.fee = fee;
    this.nonce = nonce;
    this.publicKey = publicKey;
    this.signature = signature;
    this.comment = comment != null ? comment.trim() : "";
    this.timestamp = System.currentTimeMillis();
    this.status = "pending";
    this.hash = calculateHash();
  }

  // Формирует детерминированную каноническую строку для подписи транзакции строго с chainId
  public static String buildCanonicalData(String chainId, String from, String to, long amount, long fee, long nonce, String comment) {
    String pChain = (chainId != null ? chainId.trim() : "") + "|";
    String pFrom = from != null ? from.toLowerCase() : "";
    String pTo = to != null ? to.toLowerCase() : "";
    String pComment = comment != null ? comment.trim() : "";
    return !pComment.isEmpty()
      ? pChain + pFrom + "|" + pTo + "|" + amount + "|" + fee + "|" + nonce + "|" + pComment
      : pChain + pFrom + "|" + pTo + "|" + amount + "|" + fee + "|" + nonce;
  }

  // Вычисляет уникальный SHA-256 хеш транзакции по ее содержимому
  public String calculateHash() {
    String pChain = (chainId != null ? chainId.trim() : "") + "|";
    String payload = pChain + from + "|" + to + "|" + amount + "|" + fee + "|" + nonce + "|"
      + (comment != null ? comment : "") + "|" + (signature != null ? signature : "");
    return CryptoUtil.sha256Hex(payload);
  }

  // Проверяет цифровую подпись транзакции с обязательной валидацией идентификатора сети chainId
  public boolean isSignatureValid(String expectedChainId) {
    if (publicKey == null || signature == null || from == null) return false;
    if (expectedChainId == null || expectedChainId.isBlank()) return false;
    if (chainId == null || !expectedChainId.equals(chainId)) return false;

    PublicKey pubKey = CryptoUtil.parsePublicKey(publicKey);
    if (pubKey == null) return false;

    String derived = CryptoUtil.getAddressFromPublicKey(pubKey);
    if (derived == null || !derived.equalsIgnoreCase(from)) return false;

    String canonical = buildCanonicalData(chainId, from, to, amount, fee, nonce, comment);
    return CryptoUtil.isSignatureValid(pubKey, canonical, signature);
  }
}
