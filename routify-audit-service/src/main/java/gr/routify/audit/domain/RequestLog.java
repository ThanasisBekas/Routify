package gr.routify.audit.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Records each HTTP request that passes through the API gateway.
 * Provides per-route traffic analytics and debugging capability.
 *
 * <p>Requests that result in a 5xx or unhandled error are flagged with
 * {@code failed=true} and are eligible for replay via the replay API.
 */
@Entity
@Table(
    name = "request_log",
    schema = "routify_audit",
    indexes = {
        @Index(name = "idx_req_tenant_time",   columnList = "tenant_id, requested_at DESC"),
        @Index(name = "idx_req_route",         columnList = "route_id, requested_at DESC"),
        @Index(name = "idx_req_status",        columnList = "response_status, requested_at DESC"),
        @Index(name = "idx_req_correlation",   columnList = "correlation_id"),
        @Index(name = "idx_req_failed",        columnList = "failed, requested_at DESC")
    }
)
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "route_id", updatable = false)
    private UUID routeId;

    @Column(name = "route_name", length = 255, updatable = false)
    private String routeName;

    @Column(name = "correlation_id", length = 36, updatable = false)
    private String correlationId;

    @Column(name = "http_method", length = 10, updatable = false)
    private String httpMethod;

    @Column(name = "path", length = 500, updatable = false)
    private String path;

    @Column(name = "query_string", length = 2000, updatable = false)
    private String queryString;

    @Column(name = "upstream_uri", length = 500, updatable = false)
    private String upstreamUri;

    @Column(name = "response_status", updatable = false)
    private Integer responseStatus;

    @Column(name = "duration_ms", updatable = false)
    private Long durationMs;

    @Column(name = "request_size_bytes", updatable = false)
    private Long requestSizeBytes;

    @Column(name = "response_size_bytes", updatable = false)
    private Long responseSizeBytes;

    @Column(name = "client_ip", length = 45, updatable = false)
    private String clientIp;

    @Column(name = "user_id", length = 36, updatable = false)
    private String userId;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** Which filters were applied and their execution time */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_trace", columnDefinition = "jsonb", updatable = false)
    private String filterTrace;

    /** Captured request headers (sensitive headers are redacted by the gateway) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", columnDefinition = "jsonb", updatable = false)
    private String requestHeaders;

    /** Captured response headers */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_headers", columnDefinition = "jsonb", updatable = false)
    private String responseHeaders;

    /**
     * Captured request body payload. Only populated when the REQUEST_LOGGER filter
     * has {@code logRequestBody=true}. Truncated to {@code maxBodyLogSize} bytes.
     */
    @Column(name = "request_body", columnDefinition = "text", updatable = false)
    private String requestBody;

    /**
     * Captured response body payload. Only populated when the REQUEST_LOGGER filter
     * has {@code logResponseBody=true}. Truncated to {@code maxBodyLogSize} bytes.
     */
    @Column(name = "response_body", columnDefinition = "text", updatable = false)
    private String responseBody;

    /**
     * {@code true} when the request ended with a 5xx status or unhandled exception.
     * Failed requests are eligible for replay.
     */
    @Column(name = "failed", nullable = false)
    private boolean failed;

    // ─── Replay tracking ─────────────────────────────────────────────────────

    /** Current replay state: PENDING, IN_PROGRESS, SUCCEEDED, FAILED, SKIPPED */
    @Column(name = "replay_status", length = 20)
    private String replayStatus;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "replay_count", nullable = false)
    private int replayCount = 0;

    @Column(name = "replay_response_status")
    private Integer replayResponseStatus;

    @Column(name = "replay_error", length = 1000)
    private String replayError;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected RequestLog() {}

    public static Builder builder() { return new Builder(); }

    // Getters
    public UUID getId()                    { return id; }
    public UUID getTenantId()              { return tenantId; }
    public UUID getRouteId()               { return routeId; }
    public String getRouteName()           { return routeName; }
    public String getCorrelationId()       { return correlationId; }
    public String getHttpMethod()          { return httpMethod; }
    public String getPath()                { return path; }
    public String getQueryString()         { return queryString; }
    public String getUpstreamUri()         { return upstreamUri; }
    public Integer getResponseStatus()     { return responseStatus; }
    public Long getDurationMs()            { return durationMs; }
    public String getClientIp()            { return clientIp; }
    public String getUserId()              { return userId; }
    public String getErrorMessage()        { return errorMessage; }
    public String getRequestHeaders()      { return requestHeaders; }
    public String getResponseHeaders()     { return responseHeaders; }
    public String getRequestBody()         { return requestBody; }
    public String getResponseBody()        { return responseBody; }
    public boolean isFailed()              { return failed; }
    public String getReplayStatus()        { return replayStatus; }
    public Instant getReplayedAt()         { return replayedAt; }
    public int getReplayCount()            { return replayCount; }
    public Integer getReplayResponseStatus(){ return replayResponseStatus; }
    public String getReplayError()         { return replayError; }
    public Instant getRequestedAt()        { return requestedAt; }

    // Replay setters (mutable — replay state changes over time)
    public void markReplayInProgress() {
        this.replayStatus = "IN_PROGRESS";
        this.replayCount++;
    }

    public void markReplaySucceeded(int replayStatus) {
        this.replayStatus         = "SUCCEEDED";
        this.replayedAt           = Instant.now();
        this.replayResponseStatus = replayStatus;
        this.replayError          = null;
        // Mark the request as no longer failed — the issue has been resolved via replay.
        // The original responseStatus (e.g. 500) is preserved as the immutable audit trail,
        // but failed=false removes it from the pending-replay queue.
        this.failed        = false;
        this.errorMessage  = null;
    }

    public void markReplayFailed(String error) {
        this.replayStatus = "FAILED";
        this.replayedAt = Instant.now();
        this.replayError = error;
    }

    public void markReplaySkipped(String reason) {
        this.replayStatus = "SKIPPED";
        this.replayError = reason;
    }

    public static final class Builder {
        private final RequestLog log = new RequestLog();

        public Builder tenantId(UUID v)          { log.tenantId = v; return this; }
        public Builder routeId(UUID v)           { log.routeId = v; return this; }
        public Builder routeName(String v)       { log.routeName = v; return this; }
        public Builder correlationId(String v)   { log.correlationId = v; return this; }
        public Builder httpMethod(String v)      { log.httpMethod = v; return this; }
        public Builder path(String v)            { log.path = v; return this; }
        public Builder queryString(String v)     { log.queryString = v; return this; }
        public Builder upstreamUri(String v)     { log.upstreamUri = v; return this; }
        public Builder responseStatus(int v)     { log.responseStatus = v; return this; }
        public Builder durationMs(long v)        { log.durationMs = v; return this; }
        public Builder requestSizeBytes(long v)  { log.requestSizeBytes = v; return this; }
        public Builder responseSizeBytes(long v) { log.responseSizeBytes = v; return this; }
        public Builder clientIp(String v)        { log.clientIp = v; return this; }
        public Builder userId(String v)          { log.userId = v; return this; }
        public Builder errorMessage(String v)    { log.errorMessage = v; return this; }
        public Builder filterTrace(String v)     { log.filterTrace = v; return this; }
        public Builder requestHeaders(String v)  { log.requestHeaders = v; return this; }
        public Builder responseHeaders(String v) { log.responseHeaders = v; return this; }
        public Builder requestBody(String v)     { log.requestBody = v; return this; }
        public Builder responseBody(String v)    { log.responseBody = v; return this; }
        public Builder failed(boolean v)         { log.failed = v; return this; }
        public Builder replayStatus(String v)    { log.replayStatus = v; return this; }
        public Builder requestedAt(Instant v)    { log.requestedAt = v; return this; }
        public RequestLog build()                { return log; }
    }
}
