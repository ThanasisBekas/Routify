package gr.routify.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request payload for the AI filter policy dry-run endpoint:
 * {@code POST /api/v1/admin/ai-filter/test-policy}.
 *
 * <p>Allows operators to validate a natural-language policy against a sample
 * request before activating the filter on a live route. No caching is applied
 * and no Kafka event is published for test evaluations.
 *
 * @param policyDescription The natural-language rule to test.
 * @param sampleRequest     A synthetic request to evaluate the policy against.
 */
public record TestPolicyRequest(
        @NotBlank String policyDescription,
        @NotNull  SampleRequest sampleRequest
) {

    /**
     * Synthetic HTTP request for dry-run testing.
     *
     * @param method      HTTP method (GET, POST, …).
     * @param path        Request path (e.g. /api/v1/orders).
     * @param queryString Raw query string — may be null.
     * @param clientIp    Originating client IP — may be null (defaults to "(test)").
     * @param headers     Request headers to include in the prompt — sensitive headers will be ignored.
     * @param bodyExcerpt Optional body excerpt — passed to the LLM when present.
     */
    public record SampleRequest(
            @NotBlank String method,
            @NotBlank String path,
            String queryString,
            String clientIp,
            Map<String, String> headers,
            String bodyExcerpt
    ) {}
}

