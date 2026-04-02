package gr.routify.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception for all Routify domain errors.
 * Uses Java 21 record-style companion for structured error info.
 */
public sealed class RoutifyException extends RuntimeException
        permits
            RoutifyException.NotFound,
            RoutifyException.Conflict,
            RoutifyException.Validation,
            RoutifyException.BadRequest,
            RoutifyException.Unauthorized,
            RoutifyException.Forbidden,
            RoutifyException.RateLimitExceeded,
            RoutifyException.QuotaExceeded,
            RoutifyException.GatewayError {

    private final HttpStatus status;
    private final String errorCode;

    protected RoutifyException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    protected RoutifyException(String message, HttpStatus status, String errorCode, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() { return status; }
    public String errorCode()  { return errorCode; }

    // ─── Subtypes ─────────────────────────────────────────────────────────────

    public static final class NotFound extends RoutifyException {
        public NotFound(String resource, String id) {
            super("%s with id '%s' not found".formatted(resource, id),
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        }
    }

    public static final class Conflict extends RoutifyException {
        public Conflict(String message) {
            super(message, HttpStatus.CONFLICT, "CONFLICT");
        }
    }

    public static final class Validation extends RoutifyException {
        public Validation(String message) {
            super(message, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
    }

    public static final class BadRequest extends RoutifyException {
        public BadRequest(String message) {
            super(message, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        }
    }

    public static final class Unauthorized extends RoutifyException {
        public Unauthorized(String message) {
            super(message, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    public static final class Forbidden extends RoutifyException {
        public Forbidden(String message) {
            super(message, HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    public static final class RateLimitExceeded extends RoutifyException {
        private final long retryAfterSeconds;

        public RateLimitExceeded(long retryAfterSeconds) {
            super("Rate limit exceeded. Retry after %d seconds".formatted(retryAfterSeconds),
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    public static final class QuotaExceeded extends RoutifyException {
        public QuotaExceeded(String quota) {
            super("Tenant quota exceeded: %s".formatted(quota),
                    HttpStatus.PAYMENT_REQUIRED, "QUOTA_EXCEEDED");
        }
    }

    public static final class GatewayError extends RoutifyException {
        public GatewayError(String message, Throwable cause) {
            super(message, HttpStatus.BAD_GATEWAY, "GATEWAY_ERROR", cause);
        }
        public GatewayError(String message) {
            super(message, HttpStatus.BAD_GATEWAY, "GATEWAY_ERROR");
        }
    }
}

