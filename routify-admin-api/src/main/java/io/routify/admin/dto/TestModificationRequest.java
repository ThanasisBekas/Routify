package io.routify.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request payload for the AI modifier dry-run endpoint:
 * {@code POST /api/v1/admin/ai-modifier/test-modification}.
 *
 * <p>Allows operators to validate a modification prompt against a sample
 * request before activating the filter on live traffic. No caching is applied
 * and no Kafka telemetry event is published.
 *
 * @param modificationPrompt Natural-language mutation instruction to test.
 * @param targetFields       Comma-separated targets: BODY, HEADERS, or BODY,HEADERS.
 * @param sampleRequest      A synthetic request to test the mutation against.
 */
public record TestModificationRequest(
        @NotBlank String modificationPrompt,
        String    targetFields,
        @NotNull @Valid SampleRequest sampleRequest
) {

    /**
     * Synthetic HTTP request for dry-run testing.
     *
     * @param method  HTTP method.
     * @param path    Request path.
     * @param headers Request headers.
     * @param body    Raw request body (not base64 — encoded by the controller before dispatch).
     */
    public record SampleRequest(
            @NotBlank String method,
            @NotBlank String path,
            Map<String, String> headers,
            String body
    ) {}
}

