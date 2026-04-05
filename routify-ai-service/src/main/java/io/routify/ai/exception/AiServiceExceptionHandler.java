package io.routify.ai.exception;

import io.routify.common.exception.GlobalExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * AI service exception handler.
 *
 * <p>Inherits all RFC 9457 ProblemDetail error handling from
 * {@link GlobalExceptionHandler} in routify-common. Additional handlers below
 * cover OpenAI-specific error conditions surfaced by Spring AI's exception hierarchy.
 *
 * <h3>OpenAI error taxonomy (Spring AI mappings)</h3>
 * <ul>
 *   <li><b>429 Rate Limit</b> — {@code org.springframework.ai.retry.NonTransientAiException}
 *       or {@code org.springframework.ai.retry.TransientAiException} with a 429 status code.
 *       The circuit breaker records this as a failure; after the window threshold is breached,
 *       the circuit opens and the fallback verdict is applied without further API calls.</li>
 *   <li><b>Context Length Exceeded</b> — OpenAI returns HTTP 400 with
 *       {@code error.code = "context_length_exceeded"} when the prompt exceeds the model's
 *       token limit. This is a <em>non-transient</em> error (retrying is pointless);
 *       the circuit breaker does NOT open — the request itself is malformed.</li>
 *   <li><b>Authentication / Invalid Key</b> — HTTP 401; non-transient. Indicates a
 *       misconfigured {@code OPENAI_API_KEY}; startup secret validation
 *       ({@code routify.required-secrets}) catches missing keys before the app starts.</li>
 *   <li><b>API Timeout</b> — Resilience4j TimeLimiter fires a {@code TimeoutException}
 *       before the circuit breaker sees it; the {@code llmFallback} method in the service
 *       layer handles it gracefully without surfacing an HTTP error to callers.</li>
 * </ul>
 *
 * <p>Note: in normal operation the evaluation services <em>never throw</em> to this handler
 * because all LLM errors are caught inside {@code callLlm()} / {@code llmFallback()}.
 * These handlers are a safety net for unexpected code paths (e.g. controller-level errors).
 */
@Slf4j
@RestControllerAdvice
public class AiServiceExceptionHandler extends GlobalExceptionHandler {

    // ─── OpenAI Rate Limit (429) ──────────────────────────────────────────────

    /**
     * Handles OpenAI 429 Rate Limit errors surfaced by Spring AI.
     *
     * <p>Spring AI wraps OpenAI HTTP errors in {@code NonTransientAiException} for
     * non-retryable errors and {@code TransientAiException} for retryable ones. A 429
     * that escapes Spring AI's built-in retry logic reaches here.
     *
     * <p>Returns HTTP 429 to the caller with a Retry-After hint in the response body.
     * The circuit breaker counts this as a failure; after the threshold, it opens and
     * subsequent requests get the fallback verdict instantly.
     */
    @ExceptionHandler(org.springframework.ai.retry.NonTransientAiException.class)
    public ProblemDetail handleNonTransientAiException(
            org.springframework.ai.retry.NonTransientAiException ex) {
        log.error("OpenAI non-transient error (likely 401/400/context_length_exceeded): {}",
                ex.getMessage());

        // Surface as 502 Bad Gateway — the upstream (OpenAI) returned an unrecoverable error.
        // 400 context_length_exceeded is a caller error, but we don't want to expose
        // OpenAI internals; 502 signals "upstream problem" without leaking details.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                "The AI provider returned a non-recoverable error. " +
                "Check OPENAI_API_KEY validity and prompt size.");
        pd.setType(URI.create("https://routify.gr/problems/ai-provider-error"));
        pd.setTitle("AI Provider Error");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("hint", "Possible causes: invalid API key, context length exceeded, " +
                "or unsupported model. See service logs for details.");
        return pd;
    }

    /**
     * Handles OpenAI 429 / 503 transient errors that escaped Spring AI's retry logic.
     *
     * <p>Returns HTTP 503 Service Unavailable with a Retry-After hint.
     * Callers (the API Gateway) should apply their own fallback if this propagates.
     */
    @ExceptionHandler(org.springframework.ai.retry.TransientAiException.class)
    public ProblemDetail handleTransientAiException(
            org.springframework.ai.retry.TransientAiException ex) {
        log.warn("OpenAI transient error (rate-limit / overload) — escaped retry logic: {}",
                ex.getMessage());

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The AI provider is temporarily unavailable (rate-limited or overloaded). " +
                "The circuit breaker will open if this persists. Retry after a short delay.");
        pd.setType(URI.create("https://routify.gr/problems/ai-provider-rate-limited"));
        pd.setTitle("AI Provider Rate Limited");
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("retryAfterSeconds", 30);
        pd.setProperty("hint", "If this persists, increase OPENAI_TIMEOUT_SECONDS or " +
                "reduce request concurrency (RABBITMQ_CONCURRENCY).");
        return pd;
    }
}

