package io.routify.audit.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.audit.domain.RequestLog;
import io.routify.audit.repository.RequestLogRepository;
import io.routify.common.web.RoutifyHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles replaying failed gateway requests back through the API gateway.
 *
 * <p>A replay reconstructs the original HTTP call using:
 * <ul>
 *   <li>The gateway base URL (configured via {@code routify.replay.gateway-base-url})</li>
 *   <li>The original request path and query string</li>
 *   <li>The HTTP method (GET, POST, etc.)</li>
 *   <li>The sanitised request headers (sensitive headers were already redacted by the gateway)</li>
 *   <li>The request body — if it was captured by the REQUEST_LOGGER filter (logRequestBody=true)</li>
 * </ul>
 *
 * <p>Replaying through the gateway (not directly to the upstream) ensures that the full
 * filter chain — auth, rate limiting, circuit breakers, transformations — is re-applied
 * exactly as it would be for a fresh client request.
 *
 * <p>Replay lifecycle states: {@code PENDING → IN_PROGRESS → SUCCEEDED | FAILED | SKIPPED}
 */
@Slf4j
@Service
public class FailedRequestReplayService {

    private static final int MAX_REPLAY_ATTEMPTS = 5;
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    /**
     * Internal header added to every replayed request.
     * The REQUEST_LOGGER gateway filter checks for this header and skips telemetry
     * publishing — preventing a duplicate request_log row for the replayed call.
     * The header is stripped by the gateway before reaching the upstream.
     */
    private static final String REPLAY_MARKER_HEADER = RoutifyHeaders.REPLAY_MARKER;

    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public FailedRequestReplayService(
            RequestLogRepository requestLogRepository,
            ObjectMapper objectMapper,
            @Qualifier("replayRestClient") RestClient restClient) {
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    /**
     * Replays a single failed request by its log ID.
     *
     * @param id       the UUID of the {@link RequestLog} entry
     * @param tenantId the tenant making the replay request (for authorisation)
     * @return replay result summary
     */
    @Transactional
    public ReplayResult replay(UUID id, UUID tenantId) {
        RequestLog entry = requestLogRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Request log not found: " + id));

        if (!entry.getTenantId().equals(tenantId)) {
            throw new SecurityException("Access denied: request log belongs to a different tenant");
        }

        if (!entry.isFailed()) {
            entry.markReplaySkipped("Request did not fail — replay not applicable");
            requestLogRepository.save(entry);
            return ReplayResult.skipped(id, "Request did not fail");
        }

        if (entry.getReplayCount() >= MAX_REPLAY_ATTEMPTS) {
            entry.markReplaySkipped("Maximum replay attempts (" + MAX_REPLAY_ATTEMPTS + ") exceeded");
            requestLogRepository.save(entry);
            return ReplayResult.skipped(id, "Max attempts exceeded");
        }

        if (entry.getPath() == null || entry.getPath().isBlank()) {
            entry.markReplaySkipped("No request path recorded — cannot replay");
            requestLogRepository.save(entry);
            return ReplayResult.skipped(id, "No request path");
        }

        entry.markReplayInProgress();
        requestLogRepository.save(entry);

        return executeReplay(entry);
    }

    /**
     * Bulk-replays all PENDING failed requests for the given tenant (up to {@code limit}).
     *
     * @param tenantId the owning tenant
     * @param limit    maximum number of requests to replay in this batch
     * @return summary of results
     */
    @Transactional
    public BulkReplayResult replayAll(UUID tenantId, int limit) {
        List<RequestLog> candidates = requestLogRepository
                .findReplayableRequests(Instant.now(), MAX_REPLAY_ATTEMPTS, limit)
                .stream()
                .filter(r -> tenantId.equals(r.getTenantId()))
                .toList();

        int succeeded = 0, failed = 0, skipped = 0;
        for (RequestLog entry : candidates) {
            ReplayResult result = replay(entry.getId(), tenantId);
            switch (result.outcome()) {
                case SUCCEEDED -> succeeded++;
                case FAILED    -> failed++;
                case SKIPPED   -> skipped++;
            }
        }
        return new BulkReplayResult(candidates.size(), succeeded, failed, skipped);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private ReplayResult executeReplay(RequestLog entry) {
        // Replay goes through the GATEWAY (not directly to upstream) so the full
        // filter chain (auth, rate-limit, circuit-breaker, body transform…) is re-applied.
        String path = buildPath(entry);
        HttpMethod method = parseMethod(entry.getHttpMethod());

        log.info("Replaying via gateway: id={} method={} path={} attempt={}",
                entry.getId(), method, path, entry.getReplayCount());

        try {
            RestClient.RequestBodySpec spec = restClient
                    .method(method)
                    .uri(path);

            // Mark as replay so the gateway REQUEST_LOGGER skips telemetry for this call.
            spec.header(REPLAY_MARKER_HEADER, "true");

            // Re-inject the original request headers (already sanitised by the gateway)
            injectHeaders(spec, entry.getRequestHeaders());

            // Send the captured request body when available (requires logRequestBody=true
            // on the REQUEST_LOGGER filter). Without a body, non-GET requests are replayed
            // as empty-body calls — safe for idempotent 5xx / connection-failure retries.
            String body = entry.getRequestBody();
            if (body != null && !body.isBlank()) {
                spec.body(body);
            }

            ResponseEntity<Void> response = spec
                    .retrieve()
                    .onStatus(status -> false, (req, res) -> {}) // handle all statuses manually
                    .toBodilessEntity();

            int status = response.getStatusCode().value();

            if (response.getStatusCode().is2xxSuccessful()) {
                entry.markReplaySucceeded(status);
                requestLogRepository.save(entry);
                log.info("Replay succeeded: id={} status={}", entry.getId(), status);
                return ReplayResult.succeeded(entry.getId(), status);
            } else {
                String msg = "Gateway returned non-2xx: " + status;
                entry.markReplayFailed(msg);
                requestLogRepository.save(entry);
                log.warn("Replay failed with status {}: id={}", status, entry.getId());
                return ReplayResult.failed(entry.getId(), msg);
            }

        } catch (RestClientException e) {
            String msg = "HTTP call failed: " + e.getMessage();
            entry.markReplayFailed(msg);
            requestLogRepository.save(entry);
            log.error("Replay failed: id={} error={}", entry.getId(), e.getMessage());
            return ReplayResult.failed(entry.getId(), msg);
        } catch (Exception e) {
            String msg = "Unexpected error: " + e.getMessage();
            entry.markReplayFailed(msg);
            requestLogRepository.save(entry);
            log.error("Replay error: id={}", entry.getId(), e);
            return ReplayResult.failed(entry.getId(), msg);
        }
    }

    /**
     * Builds the request path for a gateway replay.
     * Uses the recorded {@code path} (not the upstream URI) so the request
     * travels through the gateway's routing and filter chain.
     * Query string is appended when present.
     */
    private String buildPath(RequestLog entry) {
        String path = entry.getPath();
        if (path == null || path.isBlank()) path = "/";
        if (entry.getQueryString() != null && !entry.getQueryString().isBlank()) {
            return path.contains("?") ? path + "&" + entry.getQueryString()
                                      : path + "?" + entry.getQueryString();
        }
        return path;
    }

    private HttpMethod parseMethod(String method) {
        if (method == null) return HttpMethod.GET;
        return switch (method.toUpperCase()) {
            case "POST"   -> HttpMethod.POST;
            case "PUT"    -> HttpMethod.PUT;
            case "PATCH"  -> HttpMethod.PATCH;
            case "DELETE" -> HttpMethod.DELETE;
            case "HEAD"   -> HttpMethod.HEAD;
            case "OPTIONS"-> HttpMethod.OPTIONS;
            default       -> HttpMethod.GET;
        };
    }

    private void injectHeaders(RestClient.RequestBodySpec spec, String headersJson) {
        if (headersJson == null || headersJson.isBlank()) return;
        try {
            Map<String, String> headers = objectMapper.readValue(headersJson, MAP_TYPE);
            headers.forEach((name, value) -> {
                // Skip hop-by-hop headers that don't apply to replayed requests
                if (!isHopByHopHeader(name)) {
                    spec.header(name, value);
                }
            });
        } catch (Exception e) {
            log.debug("Could not parse captured headers for replay: {}", e.getMessage());
        }
    }

    private boolean isHopByHopHeader(String name) {
        return switch (name.toLowerCase()) {
            case "connection", "keep-alive", "transfer-encoding", "te",
                 "trailer", "upgrade", "proxy-authorization", "proxy-authenticate",
                 "content-length",
                 // Never re-inject our internal replay marker via captured headers
                 "x-routify-replay" -> true;
            default -> false;
        };
    }

    // ─── Result types ─────────────────────────────────────────────────────────

    public enum ReplayOutcome { SUCCEEDED, FAILED, SKIPPED }

    public record ReplayResult(UUID requestLogId, ReplayOutcome outcome, Integer responseStatus, String message) {
        static ReplayResult succeeded(UUID id, int status) {
            return new ReplayResult(id, ReplayOutcome.SUCCEEDED, status, null);
        }
        static ReplayResult failed(UUID id, String msg) {
            return new ReplayResult(id, ReplayOutcome.FAILED, null, msg);
        }
        static ReplayResult skipped(UUID id, String reason) {
            return new ReplayResult(id, ReplayOutcome.SKIPPED, null, reason);
        }
    }

    public record BulkReplayResult(int total, int succeeded, int failed, int skipped) {}
}

