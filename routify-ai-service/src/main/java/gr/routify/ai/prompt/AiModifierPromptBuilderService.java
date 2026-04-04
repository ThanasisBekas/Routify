package gr.routify.ai.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.routify.ai.dto.AiModificationRequest;
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
import java.util.Set;

/**
 * Constructs structured (System + User) prompt pairs for the AI Modification Filter.
 *
 * <h3>Prompt injection hardening</h3>
 * <ul>
 *   <li>The request body is base64-encoded before embedding in the user prompt.
 *       The system prompt instructs the LLM to decode it before transforming it —
 *       but never to treat it as instructions.</li>
 *   <li>All operator-controlled values (modificationPrompt) are sanitized to prevent
 *       template variable collisions.</li>
 *   <li>All user-controlled header values are sanitized and limited to 20 headers.</li>
 *   <li>The body is wrapped in {@code <data>} XML tags as an additional injection barrier.</li>
 * </ul>
 *
 * <h3>Output safety contract</h3>
 * The system prompt mandates that the LLM output ONLY a JSON object matching the mutation
 * schema. The service validates the output before allowing it to be re-injected into the
 * request stream — malformed output triggers passthrough, never a crash.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModifierPromptBuilderService {

    private static final Set<String> REDACTED_HEADERS = Set.of(
            "authorization", "cookie", "x-api-key", "x-auth-token",
            "x-forwarded-for", "proxy-authorization"
    );

    private final ObjectMapper objectMapper;

    private String systemPromptTemplate;
    private String userPromptTemplate;

    private String getSystemPrompt() {
        if (systemPromptTemplate == null) {
            systemPromptTemplate = loadTemplate("prompts/ai-modifier-system.txt");
        }
        return systemPromptTemplate;
    }

    private String getUserPromptTemplate() {
        if (userPromptTemplate == null) {
            userPromptTemplate = loadTemplate("prompts/ai-modifier-user.txt");
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

    /** Returns the static system prompt for the AI modifier. */
    public String buildSystemPrompt() {
        return getSystemPrompt();
    }

    /**
     * Constructs a dynamic user prompt for the mutation request.
     *
     * @param request the full modification request from the gateway
     * @return a populated user prompt ready to send to the {@code ChatClient}
     */
    public String buildUserPrompt(AiModificationRequest request) {
        AiModificationRequest.RequestContext ctx = request.requestContext();
        AiModificationRequest.AiModifierConfig cfg = request.modifierConfig();

        Map<String, String> variables = new HashMap<>();
        variables.put("modificationPrompt", sanitizeString(cfg.modificationPrompt()));
        variables.put("targetFields",       sanitizeString(cfg.targetFields()));
        variables.put("routeId",            sanitizeString(request.routeId()));
        variables.put("routeName",          sanitizeString(request.routeName()));
        variables.put("tenantId",           sanitizeString(request.tenantId()));
        variables.put("method",             sanitizeString(ctx.method()));
        variables.put("path",               sanitizeString(ctx.path()));
        variables.put("queryString",        ctx.queryString() != null ? sanitizeString(ctx.queryString()) : "(none)");
        variables.put("clientIp",           ctx.clientIp() != null ? sanitizeString(ctx.clientIp()) : "(unknown)");
        variables.put("headersJson",        buildSanitizedHeadersJson(ctx));

        // Body: always base64-encoded in the prompt (prevents injection)
        // The system prompt instructs the LLM to decode it before transforming
        if (cfg.includeBody() && ctx.bodyBase64() != null && !ctx.bodyBase64().isBlank()) {
            String truncated = truncateBase64(ctx.bodyBase64(), cfg.maxBodyBytes());
            variables.put("bodyBase64", truncated + " (base64-encoded — decode before transforming)");
        } else {
            variables.put("bodyBase64", "(body not provided — only transform headers if targeted)");
        }

        return interpolate(getUserPromptTemplate(), variables);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String sanitizeString(String value) {
        if (value == null) return "(null)";
        return value
                .replace("{", "&#123;")
                .replace("}", "&#125;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .trim();
    }

    private String buildSanitizedHeadersJson(AiModificationRequest.RequestContext ctx) {
        if (ctx.headers() == null || ctx.headers().isEmpty()) {
            return "{}";
        }
        Map<String, String> sanitized = new HashMap<>();
        ctx.headers().entrySet().stream().limit(20).forEach(e -> {
            String k = e.getKey();
            if (!REDACTED_HEADERS.contains(k.toLowerCase())) {
                sanitized.put(k, sanitizeString(e.getValue()));
            } else {
                sanitized.put(k, "[REDACTED]");
            }
        });
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize headers for modifier prompt — using empty object", e);
            return "{}";
        }
    }

    /**
     * Truncates a base64-encoded body string to ensure the decoded bytes do not exceed
     * {@code maxBytes}. Truncation is conservative — we trim the base64 string so the
     * decoded result is at most {@code maxBytes}.
     */
    private String truncateBase64(String base64, int maxBytes) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            if (decoded.length <= maxBytes) return base64;
            byte[] truncated = java.util.Arrays.copyOf(decoded, maxBytes);
            return Base64.getEncoder().encodeToString(truncated) + " (truncated)";
        } catch (IllegalArgumentException e) {
            // Not valid base64 — return as-is, LLM will handle it gracefully
            log.warn("Body excerpt is not valid base64 — forwarding raw (truncated to {} chars)", maxBytes);
            return base64.length() > maxBytes * 2 ? base64.substring(0, maxBytes * 2) : base64;
        }
    }

    private String interpolate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}

