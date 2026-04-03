package gr.routify.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for the policy dry-run endpoint:
 * {@code POST /api/v1/ai-filter/test-policy}.
 *
 * <p>Allows operators to validate a natural-language policy against a sample
 * request before activating the filter on a live route. No caching is applied
 * and no Kafka event is published for test evaluations.
 *
 * @param policyDescription     The natural-language rule to test.
 * @param sampleRequest         A synthetic request to evaluate the policy against.
 */
public record TestPolicyRequest(
        @NotBlank String policyDescription,
        @NotNull  RouteEvaluationRequest.RequestContext sampleRequest
) {}

