package io.routify.gateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Gateway filter factory that performs regex-based response header value rewriting.
 *
 * <p>Primary use cases:
 * <ul>
 *   <li>Rewriting {@code Location} redirect headers from internal to external URLs</li>
 *   <li>Rewriting {@code Set-Cookie} domain attributes</li>
 *   <li>Normalizing CORS {@code Access-Control-Allow-Origin} values</li>
 * </ul>
 *
 * <h3>Config params:</h3>
 * <ul>
 *   <li>{@code headerName} — response header to rewrite (required)</li>
 *   <li>{@code pattern} — Java regex pattern to match against the header value (required)</li>
 *   <li>{@code replacement} — replacement string supporting {@code $1}, {@code $2} capture group references (required)</li>
 *   <li>{@code replaceAll} — whether to replace all occurrences or just the first (default: {@code false})</li>
 * </ul>
 *
 * <h3>Safety:</h3>
 * <ul>
 *   <li>Regex is pre-compiled at config bind time for performance</li>
 *   <li>Pathological patterns with nested quantifiers are rejected at config time</li>
 * </ul>
 *
 * <h3>Reactive safety:</h3>
 * <p>Header modifications are registered via {@link ServerHttpResponse#beforeCommit}
 * so they are applied just before the response is committed to the wire — this
 * guarantees headers are written even for streamed/chunked responses. No blocking
 * calls are used; since pathological regex patterns are rejected at config time,
 * inline regex replacement is safe on the Netty event loop.
 *
 * <p>Filter type: {@code RESPONSE_HEADER_REWRITE}
 */
@Slf4j
@Component
public class ResponseHeaderRewriteGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ResponseHeaderRewriteGatewayFilterFactory.Config> {

    /**
     * Detects pathological regex patterns with nested quantifiers that can cause
     * catastrophic backtracking (e.g. {@code (a+)+}, {@code (a*)*}, {@code (a+)*}).
     */
    private static final Pattern NESTED_QUANTIFIER_PATTERN = Pattern.compile(
            "\\([^)]*[+*][^)]*\\)[+*?]|\\([^)]*\\{\\d+[^)]*\\)[+*?]"
    );

    public ResponseHeaderRewriteGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        // Validate required fields
        if (config.getHeaderName() == null || config.getHeaderName().isBlank()) {
            throw new IllegalArgumentException("ResponseHeaderRewrite: 'headerName' is required");
        }
        if (config.getPattern() == null || config.getPattern().isBlank()) {
            throw new IllegalArgumentException("ResponseHeaderRewrite: 'pattern' is required");
        }
        if (config.getReplacement() == null) {
            throw new IllegalArgumentException("ResponseHeaderRewrite: 'replacement' is required");
        }

        // Reject pathological regex patterns at config bind time
        validatePatternSafety(config.getPattern());

        // Pre-compile regex at config bind time — not per-request
        final Pattern compiledPattern;
        try {
            compiledPattern = Pattern.compile(config.getPattern());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "ResponseHeaderRewrite: invalid regex pattern '%s': %s"
                            .formatted(config.getPattern(), e.getMessage()), e);
        }

        final String headerName = config.getHeaderName();
        final String replacement = config.getReplacement();
        final boolean replaceAll = config.isReplaceAll();

        log.info("ResponseHeaderRewrite filter configured: header='{}' pattern='{}' replacement='{}' replaceAll={}",
                headerName, config.getPattern(), replacement, replaceAll);

        return (exchange, chain) -> {
            exchange.getResponse().beforeCommit(() -> {
                ServerHttpResponse response = exchange.getResponse();
                HttpHeaders headers = response.getHeaders();
                List<String> headerValues = headers.get(headerName);

                if (headerValues == null || headerValues.isEmpty()) {
                    return Mono.empty();
                }

                // Rewrite each value independently for multi-value headers
                List<String> rewrittenValues = new ArrayList<>(headerValues.size());
                boolean anyChanged = false;

                for (String value : headerValues) {
                    String rewritten = rewriteValue(value, compiledPattern, replacement, replaceAll);
                    rewrittenValues.add(rewritten);
                    if (!rewritten.equals(value)) {
                        anyChanged = true;
                    }
                }

                if (anyChanged) {
                    headers.put(headerName, rewrittenValues);
                    log.debug("ResponseHeaderRewrite: rewrote header '{}': {} → {}",
                            headerName, headerValues, rewrittenValues);
                }

                return Mono.empty();
            });

            return chain.filter(exchange);
        };
    }

    /**
     * Applies regex rewriting to a single header value.
     *
     * <p>Since pathological patterns (nested quantifiers) are rejected at config bind time
     * by {@link #validatePatternSafety}, the regex operation is safe to run inline on the
     * Netty event loop without timeout protection.
     *
     * @param value       the original header value
     * @param pattern     the pre-compiled regex pattern
     * @param replacement the replacement string (supports $1, $2 capture groups)
     * @param replaceAll  whether to replace all occurrences or just the first
     * @return the rewritten value, or the original value if no match
     */
    private String rewriteValue(String value, Pattern pattern, String replacement, boolean replaceAll) {
        try {
            Matcher matcher = pattern.matcher(value);
            return replaceAll ? matcher.replaceAll(replacement) : matcher.replaceFirst(replacement);
        } catch (Exception e) {
            log.warn("ResponseHeaderRewrite: regex match failed for header value '{}': {} — preserving original value",
                    value, e.getMessage());
            return value;
        }
    }

    /**
     * Validates that the regex pattern is not pathological (no nested quantifiers
     * that could cause catastrophic backtracking).
     *
     * @param pattern the regex pattern string to validate
     * @throws IllegalArgumentException if the pattern contains nested quantifiers
     */
    static void validatePatternSafety(String pattern) {
        if (NESTED_QUANTIFIER_PATTERN.matcher(pattern).find()) {
            throw new IllegalArgumentException(
                    "ResponseHeaderRewrite: rejected pathological regex pattern '%s' — "
                            .formatted(pattern)
                    + "nested quantifiers (e.g. (a+)+, (a*)*) can cause catastrophic backtracking. "
                    + "Please simplify the pattern.");
        }
    }

    @Data
    public static class Config {
        /** Response header to rewrite (required). */
        private String headerName;
        /** Java regex pattern to match against the header value (required). */
        private String pattern;
        /** Replacement string — supports $1, $2 capture group references (required). */
        private String replacement;
        /** Whether to replace all occurrences or just the first. Default: false. */
        private boolean replaceAll = false;
    }
}

