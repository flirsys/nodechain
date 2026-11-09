package so.fox.nodechain.core;

// Типизированный результат операции (Errors as Values).
public final class Result<T> {

  // Создает успешный результат выполнения.
  public static <T> Result<T> ok(T val) {
    return new Result<>(val, 200, null);
  }

  // Создает ошибочный результат с HTTP-статусом и сообщением.
  public static <T> Result<T> fail(int status, String err) {
    return new Result<>(null, status, err);
  }

  public final T value;
  public final int status;
  public final String error;
  public final boolean isOk;

  // Конструктор результата операции.
  public Result(T value, int status, String error) {
    this.value = value;
    this.status = status;
    this.error = error;
    this.isOk = error == null;
  }

  // Проверяет, произошла ли ошибка при выполнении операции.
  public boolean isError() {
    return !isOk;
  }
}
