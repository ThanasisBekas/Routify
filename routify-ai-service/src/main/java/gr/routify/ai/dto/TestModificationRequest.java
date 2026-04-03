package gr.routify.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Dry-run request payload for the AI Modification Filter test endpoint.
 *
 * <p>Called by the Routify Dashboard's "Test Modification" panel, allowing operators
 * to validate that a modification prompt produces the expected mutation before activating
 * the filter on live traffic.
 *
 * <p>No Redis caching is applied and no Kafka telemetry event is published.
 *
 * @param modificationPrompt  Natural-language mutation instruction to test.
 * @param targetFields        Comma-separated targets: BODY, HEADERS, or BODY,HEADERS.
 * @param sampleRequest       A synthetic request to test the mutation against.
 */
public record TestModificationRequest(
        @NotBlank String modificationPrompt,
        String    targetFields,
        @NotNull @Valid SampleRequest sampleRequest
) {

    /**
     * Synthetic request for dry-run testing.
     *
     * @param method      HTTP method.
     * @param path        Request path.
     * @param headers     Request headers.
     * @param body        Raw request body (not base64 — the service will encode it for the prompt).
     */
    public record SampleRequest(
            @NotBlank String method,
            @NotBlank String path,
            Map<String, String> headers,
            String body
    ) {}
}

