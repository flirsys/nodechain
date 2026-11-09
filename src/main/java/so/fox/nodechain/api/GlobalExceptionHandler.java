package so.fox.nodechain.api;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import so.fox.nodechain.api.ApiController.ErrorResponse;

// Глобальный перехватчик исключений API, унифицирует ошибки парсинга, неподдерживаемых методов и неверных типов в схему ErrorResponse.
@RestControllerAdvice
public class GlobalExceptionHandler {

  // Ошибки синтаксиса JSON, типов полей или float-десериализации в теле запроса.
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.status(400).body(new ErrorResponse(400, "Некорректный формат данных в теле запроса"));
  }

  // Ошибки типов аргументов запроса (например, limit=abc вместо int).
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.status(400).body(new ErrorResponse(400, "Некорректный тип параметра запроса"));
  }

  // Неподдерживаемые HTTP-методы (например, GET вместо POST).
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
    return ResponseEntity.status(405).body(new ErrorResponse(405, "Метод HTTP не поддерживается"));
  }

  // Неподдерживаемый Content-Type (например, text/plain вместо application/json).
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
    return ResponseEntity.status(415).body(new ErrorResponse(415, "Неподдерживаемый тип медиа"));
  }

  // Неприемлемый заголовок Accept (например, application/xml вместо JSON).
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
    return ResponseEntity.status(406).body(new ErrorResponse(406, "Неприемлемый тип медиа"));
  }

  // Ошибки отсутствия ресурса (например, неверный URL или несоответствие regex пути).
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
    return ResponseEntity.status(404).body(new ErrorResponse(404, "Ресурс не найден"));
  }

  // Общий перехватчик непредвиденных исключений без раскрытия внутренних деталей.
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
    return ResponseEntity.status(500).body(new ErrorResponse(500, "Внутренняя ошибка сервера"));
  }
}

