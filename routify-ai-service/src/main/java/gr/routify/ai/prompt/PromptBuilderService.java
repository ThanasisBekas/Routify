package gr.routify.ai.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.ai.dto.RouteEvaluationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Constructs structured (System + User) prompt pairs for the AI filter.
 *
 * <h3>Prompt injection hardening</h3>
 * <ul>
 *   <li>All user-controlled values (request headers, body) are either base64-encoded
 *       or wrapped in {@code <data>} XML tags inside the user prompt.</li>
 *   <li>The system prompt instructs the LLM never to interpret {@code <data>} content
 *       as instructions.</li>
 *   <li>A hard {@code maxBodyBytes} cap limits body surface area.</li>
 * </ul>
 *
 * <h3>Provider agnosticism</h3>
 * The prompts are plain strings — they work identically with OpenAI, Ollama,
 * Anthropic Claude, or any other {@code ChatModel} provider.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBuilderService {

    /**
     * Headers that are always redacted from the prompt regardless of gateway sanitization.
     * A defence-in-depth measure in case the gateway fails to strip them.
     */
    private static final java.util.Set<String> REDACTED_HEADERS = java.util.Set.of(
            "authorization", "cookie", "x-api-key", "x-auth-token",
            "x-forwarded-for", "proxy-authorization"
    );

    private final ObjectMapper objectMapper;

    private String systemPromptTemplate;
    private String userPromptTemplate;

    /**
     * Loads prompt templates from the classpath on first use (lazy init).
     * Templates are immutable after load — no synchronization overhead on hot path.
     */
    private String getSystemPrompt() {
        if (systemPromptTemplate == null) {
            systemPromptTemplate = loadTemplate("prompts/ai-filter-system.txt");
        }
        return systemPromptTemplate;
    }

    private String getUserPromptTemplate() {
        if (userPromptTemplate == null) {
            userPromptTemplate = loadTemplate("prompts/ai-filter-user.txt");
        }
        return userPromptTemplate;
    }

    private static String loadTemplate(String path) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt template: " + path, e);
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /** Returns the static system prompt (shared across all evaluations). */
    public String buildSystemPrompt() {
        return getSystemPrompt();
    }

    /**
     * Constructs a dynamic user prompt from the evaluation request.
     *
     * @param request the full evaluation request from the gateway
     * @return a populated user prompt string ready to send to the {@code ChatClient}
     */
    public String buildUserPrompt(RouteEvaluationRequest request) {
        RouteEvaluationRequest.RequestContext ctx = request.requestContext();
        RouteEvaluationRequest.AiFilterConfig cfg = request.filterConfig();

        Map<String, String> variables = new HashMap<>();
        variables.put("policyDescription", sanitizeString(cfg.policyDescription()));
        variables.put("routeId",           sanitizeString(request.routeId()));
        variables.put("routeName",         sanitizeString(request.routeName()));
        variables.put("tenantId",          sanitizeString(request.tenantId()));
        variables.put("method",            sanitizeString(ctx.method()));
        variables.put("path",              sanitizeString(ctx.path()));
        variables.put("queryString",       ctx.queryString() != null ? sanitizeString(ctx.queryString()) : "(none)");
        variables.put("clientIp",          ctx.clientIp() != null ? sanitizeString(ctx.clientIp()) : "(unknown)");
        variables.put("headersJson",       buildSanitizedHeadersJson(ctx));
        variables.put("userContextSection", buildUserContextSection(ctx.userContext()));

        // Body excerpt — only included if explicitly opted-in.
        // Base64-encoded to prevent any embedded prompt injection characters.
        if (cfg.includeBody() && ctx.bodyExcerpt() != null && !ctx.bodyExcerpt().isBlank()) {
            String safeBody = truncateAndEncode(ctx.bodyExcerpt(), cfg.maxBodyBytes());
            variables.put("bodyExcerpt", safeBody);
        } else {
            variables.put("bodyExcerpt", "(body not included)");
        }

        return interpolate(getUserPromptTemplate(), variables);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Sanitizes a string value before embedding in the prompt.
     * Escapes curly braces to prevent template variable collisions, and strips
     * any characters that could be interpreted as prompt injection markup.
     */
    private String sanitizeString(String value) {
        if (value == null) return "(null)";
        return value
                .replace("{", "&#123;")
                .replace("}", "&#125;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .trim();
    }

    /**
     * Builds a sanitized JSON representation of the request headers.
     * Redacts well-known sensitive headers before serialization.
     */
    private String buildSanitizedHeadersJson(RouteEvaluationRequest.RequestContext ctx) {
        if (ctx.headers() == null || ctx.headers().isEmpty()) {
            return "{}";
        }
        Map<String, String> sanitized = new HashMap<>();
        ctx.headers().forEach((k, v) -> {
            if (!REDACTED_HEADERS.contains(k.toLowerCase())) {
                sanitized.put(k, sanitizeString(v));
            } else {
                sanitized.put(k, "[REDACTED]");
            }
        });
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize headers — using empty object", e);
            return "{}";
        }
    }

    /** Builds the optional user context section of the prompt. */
    private String buildUserContextSection(RouteEvaluationRequest.UserContext userContext) {
        if (userContext == null) return "- User    : (unauthenticated)";
        return "- User    : id=%s role=%s".formatted(
                sanitizeString(userContext.userId()),
                sanitizeString(userContext.role()));
    }

    /**
     * Truncates the body to {@code maxBytes} bytes and base64-encodes it.
     * Base64 encoding prevents any injected control characters or prompt markup
     * in the body from being interpreted by the LLM.
     */
    private String truncateAndEncode(String body, int maxBytes) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            bytes = java.util.Arrays.copyOf(bytes, maxBytes);
        }
        return Base64.getEncoder().encodeToString(bytes) + " (base64-encoded)";
    }

    /** Simple {{key}} → value string interpolation. */
    private String interpolate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}

