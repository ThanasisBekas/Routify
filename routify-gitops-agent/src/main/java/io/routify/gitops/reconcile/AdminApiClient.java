package io.routify.gitops.reconcile;

import io.routify.gitops.config.GitOpsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * HTTP client for calling the admin-api import/preview and import endpoints.
 *
 * <p>Authenticates using an API key (X-Api-Key header) — not a user JWT.
 * This follows the Q3 API key infrastructure.
 */
@Component
@Slf4j
public class AdminApiClient {

    private static final String IMPORT_PREVIEW_PATH = "/api/v1/admin/routes/import/preview";
    private static final String IMPORT_APPLY_PATH = "/api/v1/admin/routes/import";

    private final RestTemplate restTemplate;
    private final GitOpsProperties properties;

    public AdminApiClient(GitOpsProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Calls the import preview endpoint with the given YAML content.
     *
     * @param yamlContent the YAML configuration to preview
     * @return the preview response as a Map, or empty if the call failed
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> previewImport(String yamlContent) {
        try {
            HttpHeaders headers = createHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-yaml"));

            HttpEntity<String> entity = new HttpEntity<>(yamlContent, headers);

            String url = properties.getAdminApiUrl() + IMPORT_PREVIEW_PATH;
            log.debug("Calling import preview: {}", url);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(response.getBody());
            }

            log.warn("Import preview returned non-success status: {}", response.getStatusCode());
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("Import preview call failed: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Calls the import apply endpoint with the given YAML content.
     *
     * @param yamlContent the YAML configuration to apply
     * @return {@code true} if the import was accepted (HTTP 202), {@code false} otherwise
     */
    public boolean applyImport(String yamlContent) {
        try {
            HttpHeaders headers = createHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-yaml"));

            HttpEntity<String> entity = new HttpEntity<>(yamlContent, headers);

            String url = properties.getAdminApiUrl() + IMPORT_APPLY_PATH;
            log.debug("Calling import apply: {}", url);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, Map.class);

            boolean accepted = response.getStatusCode() == HttpStatus.ACCEPTED
                    || response.getStatusCode().is2xxSuccessful();

            if (accepted) {
                log.info("Import apply accepted");
            } else {
                log.warn("Import apply returned unexpected status: {}", response.getStatusCode());
            }

            return accepted;
        } catch (RestClientException e) {
            log.error("Import apply call failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", properties.getApiKey());
        headers.set("X-Tenant-Id", properties.getTenantId());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }
}
