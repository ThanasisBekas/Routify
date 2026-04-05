package io.routify.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * Global exception handler using Spring 6 RFC 9457 ProblemDetail.
 * All error responses conform to the Problem JSON specification.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final URI PROBLEM_BASE = URI.create("https://routify.io/problems/");

    @ExceptionHandler(RoutifyException.class)
    public ResponseEntity<ProblemDetail> handleRoutifyException(
            RoutifyException ex, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(PROBLEM_BASE.resolve(ex.errorCode().toLowerCase().replace('_', '-')));
        problem.setTitle(titleize(ex.errorCode()));
        problem.setProperty("errorCode", ex.errorCode());
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("path", request.getRequestURI());

        // Extra field for rate limit
        if (ex instanceof RoutifyException.RateLimitExceeded rle) {
            problem.setProperty("retryAfter", rle.retryAfterSeconds());
        }
        return ResponseEntity.status(ex.status()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();

        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Request validation failed");
        problem.setType(PROBLEM_BASE.resolve("validation-error"));
        problem.setTitle("Validation Error");
        problem.setProperty("errors", errors);
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("path", request.getRequestURI());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        var errors = ex.getConstraintViolations().stream()
                .map(cv -> new FieldViolation(
                        cv.getPropertyPath().toString(),
                        cv.getMessage()))
                .toList();

        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Constraint violation");
        problem.setType(PROBLEM_BASE.resolve("validation-error"));
        problem.setTitle("Validation Error");
        problem.setProperty("errors", errors);
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("path", request.getRequestURI());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(
            Exception ex, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(PROBLEM_BASE.resolve("internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("errorCode", "INTERNAL_ERROR");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("path", request.getRequestURI());
        return ResponseEntity.internalServerError().body(problem);
    }

    private static FieldViolation toFieldError(FieldError fe) {
        return new FieldViolation(fe.getField(), fe.getDefaultMessage());
    }

    private static String titleize(String errorCode) {
        return errorCode.replace('_', ' ')
                .chars()
                .collect(StringBuilder::new, (sb, c) -> {
                    if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ' ') {
                        sb.append((char) Character.toUpperCase(c));
                    } else if (sb.isEmpty()) {
                        sb.append((char) Character.toUpperCase(c));
                    } else {
                        sb.append((char) Character.toLowerCase(c));
                    }
                }, StringBuilder::append)
                .toString();
    }

    public record FieldViolation(String field, String message) {}
}

