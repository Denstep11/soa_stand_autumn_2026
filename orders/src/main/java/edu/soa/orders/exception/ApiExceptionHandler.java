package edu.soa.orders.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<?> invalidBody(Exception ignored) {
    return ResponseEntity.badRequest().body(Map.of("error", "bad_request"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException error) {
    return ResponseEntity.status(error.getStatusCode())
        .body(Map.of("error", error.getStatusCode().value() == 404 ? "not_found" : "bad_request"));
  }
}
