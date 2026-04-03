package gr.routify.ai.exception;

import gr.routify.common.exception.GlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AI service exception handler.
 *
 * <p>Inherits all RFC 9457 ProblemDetail error handling from
 * {@link GlobalExceptionHandler} in routify-common. No AI-specific exceptions
 * are added here because the evaluation service always returns a verdict
 * (never throws) — fallback is applied on all failure paths.
 */
@RestControllerAdvice
public class AiServiceExceptionHandler extends GlobalExceptionHandler {
    // Inherits: RoutifyException, MethodArgumentNotValidException,
    //           ConstraintViolationException, and generic Exception handlers.
    // Add AI-specific @ExceptionHandler methods below if needed in future phases.
}

