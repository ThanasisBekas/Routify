package io.routify.gitops.reconcile;

import io.routify.gitops.config.GitOpsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminApiClient} — import preview, import apply,
 * header construction, and error handling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminApiClient")
class AdminApiClientTest {

    @Mock
    private RestTemplate restTemplate;

    private GitOpsProperties properties;
    private AdminApiClient client;

    @BeforeEach
    void setUp() {
        properties = new GitOpsProperties();
        properties.setRepositoryUrl("https://github.com/test/repo.git");
        properties.setAdminApiUrl("http://localhost:8082");
        properties.setApiKey("test-api-key-123");
        properties.setTenantId("tenant-abc");
        client = new AdminApiClient(properties, restTemplate);
    }

    // ── previewImport ─────────────────────────────────────────────────

    @Test
    @DisplayName("previewImport returns parsed response on 200 OK")
    @SuppressWarnings("unchecked")
    void previewImport_success_returnsParsedMap() {
        Map<String, Object> body = Map.of("routesToCreate", 3, "valid", true);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

        Optional<Map<String, Object>> result = client.previewImport("yaml-content");

        assertThat(result).isPresent();
        assertThat(result.get().get("routesToCreate")).isEqualTo(3);
    }

    @Test
    @DisplayName("previewImport calls correct URL")
    @SuppressWarnings("unchecked")
    void previewImport_correctUrl() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        client.previewImport("yaml");

        verify(restTemplate).exchange(
                eq("http://localhost:8082/api/v1/admin/routes/import/preview"),
                eq(HttpMethod.POST), any(), eq(Map.class));
    }

    @Test
    @DisplayName("previewImport throws on RestClientException (retry fallback handles this in production)")
    void previewImport_networkError_throws() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        // Without Spring AOP, the @Retry fallback doesn't activate.
        // In production, Resilience4j retries 3 times then calls previewImportFallback().
        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> client.previewImport("yaml"));
        assertThat(ex.getMessage()).contains("Connection refused");
    }

    @Test
    @DisplayName("previewImport returns empty when response body is null")
    @SuppressWarnings("unchecked")
    void previewImport_nullBody_returnsEmpty() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok().body(null));

        Optional<Map<String, Object>> result = client.previewImport("yaml");

        assertThat(result).isEmpty();
    }

    // ── applyImport ──────────────────────────────────────────────────

    @Test
    @DisplayName("applyImport returns true on HTTP 202 Accepted")
    @SuppressWarnings("unchecked")
    void applyImport_202_returnsTrue() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.ACCEPTED));

        boolean result = client.applyImport("yaml");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("applyImport returns true on HTTP 200 OK")
    @SuppressWarnings("unchecked")
    void applyImport_200_returnsTrue() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        boolean result = client.applyImport("yaml");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("applyImport calls correct URL")
    @SuppressWarnings("unchecked")
    void applyImport_correctUrl() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.ACCEPTED));

        client.applyImport("yaml");

        verify(restTemplate).exchange(
                eq("http://localhost:8082/api/v1/admin/routes/import"),
                eq(HttpMethod.POST), any(), eq(Map.class));
    }

    // ── createHeaders ────────────────────────────────────────────────

    @Test
    @DisplayName("createHeaders sets X-Api-Key header")
    void createHeaders_apiKey() {
        HttpHeaders headers = client.createHeaders();

        assertThat(headers.getFirst("X-Api-Key")).isEqualTo("test-api-key-123");
    }

    @Test
    @DisplayName("createHeaders sets X-Tenant-Id header")
    void createHeaders_tenantId() {
        HttpHeaders headers = client.createHeaders();

        assertThat(headers.getFirst("X-Tenant-Id")).isEqualTo("tenant-abc");
    }

    @Test
    @DisplayName("createHeaders sets Accept: application/json")
    void createHeaders_acceptJson() {
        HttpHeaders headers = client.createHeaders();

        assertThat(headers.getAccept()).containsExactly(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("previewImport sends Content-Type: application/x-yaml")
    @SuppressWarnings("unchecked")
    void previewImport_contentType() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        client.previewImport("yaml");

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));

        assertThat(captor.getValue().getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("application/x-yaml"));
    }
}

