// ─── Common types ─────────────────────────────────────────────────────────────

/** Returned by all write (Kafka command) endpoints — HTTP 202 Accepted. */
export interface AsyncAcknowledgement {
  status: string
  message: string
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  page: number
  first: boolean
  last: boolean
}

export interface ApiError {
  type: string
  title: string
  status: number
  detail: string
  errorCode?: string
  errors?: { field: string; message: string }[]
  timestamp?: string
  path?: string
}

// ─── Auth ─────────────────────────────────────────────────────────────────────

export type UserRole = 'SUPER_ADMIN' | 'TENANT_ADMIN' | 'VIEWER' | 'OPERATOR'

export interface UserInfo {
  id: string
  tenantId: string
  username: string
  email: string
  role: UserRole
  mustChangePassword?: boolean
}

export interface LoginResponse {
  accessToken: string
  /** Refresh token is NOT present in the response body — it is delivered as an HttpOnly cookie. */
  refreshToken?: never
  tokenType: string
  expiresIn: number
  user: UserInfo
  mustChangePassword: boolean
}

// ─── Tenant ───────────────────────────────────────────────────────────────────

export type TenantPlan = 'FREE' | 'STARTER' | 'PRO' | 'ENTERPRISE'
export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED'

/** Lightweight workspace descriptor used in the login-page dropdown. */
export interface WorkspaceOption {
  name: string
  slug: string
}

export interface TenantDto {
  id: string
  name: string
  slug: string
  status: TenantStatus
  plan: TenantPlan
  contactEmail?: string
  createdAt: string
}

// ─── Routes ───────────────────────────────────────────────────────────────────

export type RouteStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED'

export interface RouteFilterRef {
  filterId: string
  filterName: string
  filterType: FilterType
  order: number
  phase: 'PRE' | 'POST'
  enabled: boolean
}

export interface RouteDto {
  id: string
  tenantId: string
  name: string
  description?: string
  pathPattern: string
  methods: string
  upstreamUri: string
  stripPrefix?: string
  status: RouteStatus
  version: number
  filters: RouteFilterRef[]
  extraConfig?: Record<string, unknown>
  createdBy?: string
  createdAt: string
  updatedAt: string
  activatedAt?: string
}

export interface RouteSummary {
  id: string
  name: string
  description?: string
  pathPattern: string
  methods: string
  upstreamUri: string
  status: RouteStatus
  version: number
  filterCount: number
  preFilterCount: number
  postFilterCount: number
  createdAt: string
  activatedAt?: string
}

export interface CreateRouteRequest {
  name: string
  description?: string
  pathPattern: string
  methods: string
  upstreamUri: string
  stripPrefix?: string
  extraConfig?: Record<string, unknown>
}

export interface UpdateRouteRequest {
  name?: string
  description?: string
  pathPattern?: string
  methods?: string
  upstreamUri?: string
  stripPrefix?: string
  extraConfig?: Record<string, unknown>
}

export interface AttachFilterRequest {
  filterId: string
  order: number
  phase: 'PRE' | 'POST'
}

// ─── Filters ──────────────────────────────────────────────────────────────────

export type FilterType =
  | 'AUTH_API_KEY' | 'AUTH_BASIC' | 'AUTH_JWT' | 'AUTH_MTLS' | 'AUTH_OAUTH2' | 'AUTH_CLIENT_ID' | 'AUTH_CERT_VAULT'
  | 'DOWNSTREAM_BASIC_AUTH' | 'DOWNSTREAM_BEARER_CC'
  | 'RATE_LIMIT_FIXED_WINDOW' | 'RATE_LIMIT_SLIDING_WINDOW'
  | 'REQUEST_HEADER_MODIFY' | 'RESPONSE_HEADER_MODIFY'
  | 'BODY_JOLT_TRANSFORM'
  | 'VALIDATE_JSON_SCHEMA'
  | 'TIMEOUT'
  | 'CONDITIONAL_ROUTE' | 'USER_ID_PAYLOAD_ROUTING'
  | 'CERT_ROTATION' | 'CERT_VAULT_EXPIRY_CHECK'
  | 'API_VERSIONING'
  | 'CORRELATION_ID' | 'REQUEST_LOGGER' | 'TENANT_CONTEXT' | 'SECURITY_HEADERS' | 'CUSTOM_METRIC'
  | 'CUSTOM_SPEL'
  // ─── AI ──────────────────────────────────────────────────────────────────
  | 'AI_FILTER'
  | 'AI_MODIFIER'

export type FilterCategory =
  | 'Authentication' | 'Downstream Auth' | 'Rate Limiting' | 'Request Modification'
  | 'Body Transformation' | 'Validation' | 'Resilience'
  | 'Routing' | 'Security' | 'Versioning' | 'Observability' | 'Custom'


export interface FilterDefinitionDto {
  id: string
  tenantId: string
  name: string
  description?: string
  filterType: FilterType
  config: Record<string, unknown>
  systemManaged: boolean
  enabled: boolean
  usageCount: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface FilterSummary {
  id: string
  name: string
  filterType: FilterType
  enabled: boolean
  usageCount: number
  createdAt: string
}

export interface CreateFilterRequest {
  name: string
  description?: string
  filterType: FilterType
  config: Record<string, unknown>
}

export interface UpdateFilterRequest {
  name?: string
  description?: string
  config?: Record<string, unknown>
}

// ─── Audit ────────────────────────────────────────────────────────────────────

export interface AuditEntry {
  eventId: string
  tenantId: string
  eventType: string
  aggregateType: string
  aggregateId: string
  actorId?: string
  correlationId?: string
  occurredAt: string
  recordedAt: string
}

export interface RequestLogDto {
  id: string
  tenantId: string
  routeId: string
  routeName: string
  correlationId?: string
  method: string
  path: string
  queryString?: string
  upstreamUri?: string
  responseStatus?: number
  durationMs?: number
  clientIp?: string
  userId?: string
  errorMessage?: string
  /** Sanitised request headers captured at gateway (sensitive headers redacted).
   *  May arrive as a JSON-encoded string or a pre-parsed object depending on the API serialisation. */
  requestHeaders?: Record<string, string> | string
  /** Sanitised response headers captured at gateway.
   *  May arrive as a JSON-encoded string or a pre-parsed object depending on the API serialisation. */
  responseHeaders?: Record<string, string> | string
  /** Request body payload — only present when REQUEST_LOGGER logRequestBody=true */
  requestBody?: string
  /** Response body payload — only present when REQUEST_LOGGER logResponseBody=true */
  responseBody?: string
  requestedAt: string
  failed: boolean
  replayStatus?: ReplayStatus
  replayCount: number
  replayedAt?: string
  replayResponseStatus?: number
  replayError?: string
}

export type ReplayStatus = 'PENDING' | 'IN_PROGRESS' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'

export interface FailedRequestDto {
  id: string
  tenantId: string
  routeId?: string
  routeName?: string
  correlationId?: string
  method: string
  path: string
  queryString?: string
  upstreamUri?: string
  responseStatus?: number
  durationMs?: number
  clientIp?: string
  userId?: string
  errorMessage?: string
  requestHeaders?: Record<string, string> | string
  responseHeaders?: Record<string, string> | string
  requestBody?: string
  responseBody?: string
  requestedAt: string
  failed: boolean
  replayStatus?: ReplayStatus
  replayCount: number
  replayedAt?: string
  replayResponseStatus?: number
  replayError?: string
}

export interface ReplayStats {
  pending: number
  inProgress: number
  succeeded: number
  failed: number
  skipped: number
}

export interface ReplayResult {
  requestLogId: string
  outcome: 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
  responseStatus?: number
  message?: string
}

export interface BulkReplayResult {
  total: number
  succeeded: number
  failed: number
  skipped: number
}

export interface RouteStats {
  routeId: string
  totalRequests: number
  avgDurationMs: number
  maxDurationMs: number
  errorCount: number
}

// ─── Users ────────────────────────────────────────────────────────────────────

export interface UserDto {
  id: string
  tenantId: string
  username: string
  email: string
  role: UserRole
  status: string
  mustChangePassword?: boolean
  lastLoginAt?: string
  createdAt: string
}

export interface CreateUserRequest {
  username: string
  email: string
  password: string
  role: UserRole
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

export interface DashboardStats {
  routes?: { total: number; active: number; draft: number; disabled: number }
  filters?: { total: number; inUse: number }
  requests?: { last24h: number; errorRate: number; avgLatencyMs: number }
  gateway?: { status: 'UP' | 'DOWN' | 'DEGRADED'; loadedRoutes: number }
}

// ─── SSE Events ───────────────────────────────────────────────────────────────

export type DashboardEventType =
  | 'connected' | 'route.created' | 'route.updated'
  | 'route.activated' | 'route.deactivated' | 'route.deleted'
  | 'filter.created' | 'filter.updated' | 'filter.deleted'
  | 'filter.attached' | 'filter.detached'
  | 'gateway.reloaded' | 'gateway.config.changed'

// ─── Gateway Configuration ────────────────────────────────────────────────────

export interface GatewayCorsConfig {
  enabled: boolean
  allowedOriginPatterns: string[]
  allowedMethods: string[]
  allowedHeaders: string[]
  exposedHeaders: string[]
  allowCredentials: boolean
  maxAge: number
  paths: string[]
}

export interface GatewaySecurityHeadersConfig {
  enabled: boolean
  xContentTypeOptions: boolean
  xFrameOptions: boolean
  xFrameOptionsValue: 'DENY' | 'SAMEORIGIN'
  xXssProtection: boolean
  strictTransportSecurity: boolean
  stsMaxAge: number
  stsIncludeSubDomains: boolean
  stsPreload: boolean
  referrerPolicy: string
  permissionsPolicy: string
  contentSecurityPolicy?: string
  removeServerHeader: boolean
  removePoweredByHeader: boolean
  customHeaders?: Record<string, string>
}

export interface GatewayRateLimitPolicy {
  id: string
  name: string
  description?: string
  algorithm: 'TOKEN_BUCKET' | 'FIXED_WINDOW' | 'SLIDING_WINDOW'
  keyResolver: 'IP' | 'USER' | 'TENANT' | 'API_KEY'
  replenishRate: number
  burstCapacity: number
  requestedTokens: number
  windowMs: number
  enabled: boolean
  globalPaths?: string[]
}

export interface GatewayCircuitBreakerDefaults {
  slidingWindowType: 'COUNT_BASED' | 'TIME_BASED'
  slidingWindowSize: number
  minimumNumberOfCalls: number
  failureRateThreshold: number
  slowCallRateThreshold: number
  slowCallDurationThresholdMs: number
  waitDurationInOpenState: string
  permittedNumberOfCallsInHalfOpenState: number
  automaticTransitionFromOpenToHalfOpen: boolean
  fallbackUri: string
  recordExceptions: boolean
  recordExceptionClasses?: string[]
  ignoreExceptionClasses?: string[]
}

export interface GatewayResilienceDefaults {
  retryMaxAttempts: number
  retryWaitDuration: string
  retryExponentialBackoff: boolean
  retryExponentialMultiplier: number
  retryMaxWaitDuration: string
  retryExceptions?: string[]
  timeoutDuration: string
  timeoutCancelRunningFuture: boolean
  bulkheadEnabled: boolean
  bulkheadMaxConcurrentCalls: number
  bulkheadMaxWaitDuration: number
}

export interface GatewayAuthProvider {
  id: string
  name: string
  type: 'OAUTH2_CLIENT_CREDENTIALS' | 'OAUTH2_PASSWORD' | 'OAUTH2_INTROSPECT' | 'BASIC' | 'JWT_VERIFY'
  uri?: string
  clientId?: string
  clientSecret?: string
  scope?: string
  username?: string
  password?: string
  parameterStyle?: 'BODY' | 'HEADER'
  parameterName?: string
  additionalParameters?: Record<string, string>
  enabled: boolean
  jwksUri?: string
  issuer?: string
  audience?: string
  algorithm?: string
}


export interface GatewayProxyConfig {
  enabled: boolean
  host?: string
  port?: number
  username?: string
  password?: string
  nonProxyHosts: string[]
  type: 'HTTP' | 'HTTPS' | 'SOCKS5'
}

export interface GatewayHttpClientConfig {
  connectTimeoutMs: number
  responseTimeoutMs: number
  maxConnections: number
  maxConnectionsPerRoute: number
  acquireTimeoutMs: number
  maxIdleTime: string
  maxLifeTime: string
  compressionEnabled: boolean
  followRedirects: boolean
  wiretapEnabled: boolean
}

export interface GatewayCorrelationIdConfig {
  enabled: boolean
  headerName: string
  generateIfMissing: boolean
  propagateToResponse: boolean
}

export interface GatewayRequestLoggerConfig {
  enabled: boolean
  logRequestHeaders: boolean
  logResponseHeaders: boolean
  logRequestBody: boolean
  logResponseBody: boolean
  maxBodyLogSize: number
  excludePaths: string[]
  maskHeaders: string[]
}


export interface GatewayTenantIsolationConfig {
  enabled: boolean
  tenantIdHeader: string
  allowCrossTenantsForSuperAdmin: boolean
}


export interface GatewayConfig {
  updatedAt?: string
  updatedBy?: string
  cors: GatewayCorsConfig
  securityHeaders: GatewaySecurityHeadersConfig
  rateLimitPolicies: GatewayRateLimitPolicy[]
  circuitBreakerDefaults: GatewayCircuitBreakerDefaults
  resilienceDefaults: GatewayResilienceDefaults
  authProviders: GatewayAuthProvider[]
  proxyConfig: GatewayProxyConfig
  httpClientConfig: GatewayHttpClientConfig
  tenantIsolation: GatewayTenantIsolationConfig
}

export interface GatewayLiveStatus {
  health: { status: string; components?: Record<string, unknown> }
  routes: { count: number; routes: unknown[] }
  circuitBreakers: Record<string, unknown>
}

// ─── Certificate Vault ────────────────────────────────────────────────────────

export type CertFormat = 'PEM' | 'PKCS12'
export type CertStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'DELETED'
export type CertExpiryStatus = 'VALID' | 'EXPIRING_SOON' | 'EXPIRED'
export type CertGroupStatus = 'ACTIVE' | 'ARCHIVED'

export interface CertificateDto {
  id: string
  tenantId: string
  logicalId: string
  alias: string
  description?: string
  format: CertFormat
  status: CertStatus
  expiryStatus: CertExpiryStatus
  subjectDn?: string
  issuerDn?: string
  serialNumber?: string
  notBefore?: string
  notAfter?: string
  signatureAlg?: string
  keyAlgorithm?: string
  keySize?: number
  fingerprintSha1?: string
  fingerprintSha256?: string
  sanDns?: string[]
  sanIp?: string[]
  isCa: boolean
  hasPrivateKey: boolean
  // Direct gateway mapping (standalone / legacy)
  gatewayTlsLogicalId?: string
  // Group membership
  groupId?: string
  groupLogicalId?: string
  memberAlias?: string
  /** Gateway registry key: groupLogicalId if grouped, else gatewayTlsLogicalId */
  effectiveGatewayLogicalId?: string
  uploadedBy?: string
  createdAt: string
  updatedAt: string
  expiresAt?: string
}

/**
 * Certificate Group DTO — the logical container that the gateway TLS registry and
 * filters bind to via its stable {@code logicalId}.
 */
export interface CertGroupDto {
  id: string
  tenantId: string
  /** Stable key used by the gateway TLS registry and CertRotation / CertVaultAuth filters */
  logicalId: string
  alias: string
  description?: string
  status: CertGroupStatus
  memberCount: number
  /** Worst-case expiry health across active members: VALID | EXPIRING_SOON | EXPIRED */
  expiryHealthStatus: CertExpiryStatus
  /** Present only in detail GET responses */
  members?: CertificateDto[]
  createdBy?: string
  createdAt: string
  updatedAt?: string
}

export interface CreateCertGroupRequest {
  logicalId: string
  alias: string
  description?: string
}

export interface UpdateCertGroupRequest {
  alias?: string
  description?: string
}


export interface UploadCertificateRequest {
  /** Mandatory: group this certificate belongs to */
  groupId: string
  /** Optional short label within the group (e.g. "primary", "backup-2025") */
  memberAlias?: string
  alias: string
  description?: string
  format: CertFormat
  certPem: string
  privateKey?: string
}

export interface CertVaultStats {
  total: number
  active: number
  expiringSoon: number
  counts: Record<string, number>
}

// ─── AI Filter / Modifier types ───────────────────────────────────────────────

export type AiMutationType = 'PII_SCRUB' | 'TRANSLATE' | 'HEADER_REWRITE' | 'CUSTOM' | 'PASSTHROUGH'

/** Request payload for the AI Modification dry-run test endpoint. */
export interface AiModificationTestRequest {
  modificationPrompt: string
  targetFields?: string
  sampleRequest: {
    method: string
    path: string
    headers?: Record<string, string>
    body?: string
  }
}

/** Response from the AI Modification dry-run test endpoint. */
export interface AiModificationTestResult {
  mutationId: string
  mutationApplied: boolean
  mutationType: AiMutationType
  mutatedHeaders: Record<string, string>
  mutatedBody: string | null
  reason: string
  cached: boolean
  latencyMs: number
}

/** Aggregated AI Modification Filter stats per route from the audit service. */
export interface AiModifierStats {
  routeId: string
  totalDecisions: number
  appliedCount: number
  passthroughCount: number
  piiScrubCount: number
  translateCount: number
  headerRewriteCount: number
  customCount: number
  cacheHitCount: number
  avgLatencyMs: number
  p95LatencyMs: number
  p99LatencyMs: number
  from: string
  to: string
}

/** Aggregated AI Filter stats per route from the audit service. */
export interface AiFilterStats {
  routeId: string
  totalDecisions: number
  allowCount: number
  blockCount: number
  flagCount: number
  fallbackCount: number
  cacheHitCount: number
  avgLatencyMs: number
  p95LatencyMs: number
  p99LatencyMs: number
  from: string
  to: string
}

/** A single AI filter decision audit entry. */
export interface AiFilterDecisionEntry {
  evaluationId: string
  routeId: string
  routeName: string
  action: 'ALLOW' | 'BLOCK' | 'FLAG'
  reason: string
  confidence: number
  cached: boolean
  latencyMs: number
  method: string
  path: string
  evaluatedAt: string
}

/** A single AI modification decision audit entry. */
export interface AiModifierDecisionEntry {
  mutationId: string
  routeId: string
  routeName: string
  mutationApplied: boolean
  mutationType: AiMutationType
  reason: string
  headersModified: string[]
  cached: boolean
  latencyMs: number
  method: string
  path: string
  evaluatedAt: string
}


