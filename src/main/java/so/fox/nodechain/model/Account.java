package so.fox.nodechain.model;

import java.io.Serializable;

// Модель аккаунта блокчейна: адрес, подтвержденный баланс и nonce.
public class Account implements Serializable {
  private static final long serialVersionUID = 1L;

  public String address;
  public long balance;
  public long nonce;
  public long updatedAt;

  // Конструктор по умолчанию для сериализации.
  public Account() {}

  // Конструктор инициализации аккаунта с заданным адресом, балансом и nonce.
  public Account(String address, long balance, long nonce) {
    this.address = address != null ? address.trim().toLowerCase() : "";
    this.balance = balance;
    this.nonce = nonce;
    this.updatedAt = System.currentTimeMillis();
  }

  // Увеличивает баланс на указанную сумму и обновляет timestamp.
  public void addBalance(long amount) {
    this.balance = Math.addExact(this.balance, amount);
    this.updatedAt = System.currentTimeMillis();
  }

  // Уменьшает баланс на указанную сумму и обновляет timestamp.
  public void subtractBalance(long amount) {
    this.balance = Math.subtractExact(this.balance, amount);
    this.updatedAt = System.currentTimeMillis();
  }

  // Корректирует баланс аккаунта на указанную дельту без проверки промежуточного переполнения (для отката блока).
  public void adjustBalanceUnchecked(long delta) {
    this.balance += delta;
    this.updatedAt = System.currentTimeMillis();
  }

  // Увеличивает значение nonce на единицу.
  public void incrementNonce() {
    this.nonce++;
    this.updatedAt = System.currentTimeMillis();
  }
}
