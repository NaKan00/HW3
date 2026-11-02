package edu.hw3.booking;

import io.grpc.StatusRuntimeException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class RestErrors {
  public record ErrorBody(String errorCode, String message, Map<String, Object> details) {}

  @ExceptionHandler(StatusRuntimeException.class)
  public ResponseEntity<ErrorBody> grpc(StatusRuntimeException e) {
    int code =
        switch (e.getStatus().getCode()) {
          case INVALID_ARGUMENT -> 400;
          case NOT_FOUND -> 404;
          case ALREADY_EXISTS, RESOURCE_EXHAUSTED, FAILED_PRECONDITION -> 409;
          case UNAVAILABLE, DEADLINE_EXCEEDED, UNAUTHENTICATED, PERMISSION_DENIED -> 503;
          default -> 500;
        };
    String message =
        code == 503 ? "Flight service temporarily unavailable" : e.getStatus().getDescription();
    return ResponseEntity.status(code)
        .body(new ErrorBody(e.getStatus().getCode().name(), message, Map.of()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    jakarta.validation.ConstraintViolationException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<ErrorBody> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(new ErrorBody("INVALID_ARGUMENT", "Invalid request parameters or body", Map.of()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorBody> internal(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("REST_FAILURE type={}", e.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(new ErrorBody("INTERNAL", "Booking operation failed", Map.of()));
  }
}
