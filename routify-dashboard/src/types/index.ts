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

/** Fine-grained RBAC permission constants — mirrors `io.routify.common.domain.Permission`. */
export type Permission =
  | 'ROUTES_READ'
  | 'ROUTES_WRITE'
  | 'ROUTES_ACTIVATE'
  | 'ROUTES_DELETE'
  | 'ROUTES_PROMOTE'
  | 'FILTERS_READ'
  | 'FILTERS_WRITE'
  | 'FILTERS_DELETE'
  | 'USERS_READ'
  | 'USERS_WRITE'
  | 'USERS_DELETE'
  | 'CERTS_READ'
  | 'CERTS_WRITE'
  | 'CERTS_ADMIN'
  | 'AUDIT_READ'
  | 'AUDIT_REPLAY'
  | 'GATEWAY_CONFIG_READ'
  | 'GATEWAY_CONFIG_WRITE'
  | 'API_KEYS_READ'
  | 'API_KEYS_ADMIN'
  | 'WEBHOOKS_READ'
  | 'WEBHOOKS_ADMIN'
  | 'AI_POLICY_READ'
  | 'AI_POLICY_WRITE'
  | 'TENANTS_READ'
  | 'TENANTS_WRITE'
  | 'TENANTS_SUSPEND'

/** All available permissions — useful for form builders. */
export const ALL_PERMISSIONS: Permission[] = [
  'ROUTES_READ',
  'ROUTES_WRITE',
  'ROUTES_ACTIVATE',
  'ROUTES_DELETE',
  'ROUTES_PROMOTE',
  'FILTERS_READ',
  'FILTERS_WRITE',
  'FILTERS_DELETE',
  'USERS_READ',
  'USERS_WRITE',
  'USERS_DELETE',
  'CERTS_READ',
  'CERTS_WRITE',
  'CERTS_ADMIN',
  'AUDIT_READ',
  'AUDIT_REPLAY',
  'GATEWAY_CONFIG_READ',
  'GATEWAY_CONFIG_WRITE',
  'API_KEYS_READ',
  'API_KEYS_ADMIN',
  'WEBHOOKS_READ',
  'WEBHOOKS_ADMIN',
  'AI_POLICY_READ',
  'AI_POLICY_WRITE',
  'TENANTS_READ',
  'TENANTS_WRITE',
  'TENANTS_SUSPEND',
]

/** Groups permissions by resource type for form builders. */
export const PERMISSION_GROUPS: Record<string, Permission[]> = {
  Routes: ['ROUTES_READ', 'ROUTES_WRITE', 'ROUTES_ACTIVATE', 'ROUTES_DELETE', 'ROUTES_PROMOTE'],
  Filters: ['FILTERS_READ', 'FILTERS_WRITE', 'FILTERS_DELETE'],
  Users: ['USERS_READ', 'USERS_WRITE', 'USERS_DELETE'],
  Certificates: ['CERTS_READ', 'CERTS_WRITE', 'CERTS_ADMIN'],
  Audit: ['AUDIT_READ', 'AUDIT_REPLAY'],
  'Gateway Config': ['GATEWAY_CONFIG_READ', 'GATEWAY_CONFIG_WRITE'],
  'API Keys': ['API_KEYS_READ', 'API_KEYS_ADMIN'],
  Webhooks: ['WEBHOOKS_READ', 'WEBHOOKS_ADMIN'],
  AI: ['AI_POLICY_READ', 'AI_POLICY_WRITE'],
  Tenants: ['TENANTS_READ', 'TENANTS_WRITE', 'TENANTS_SUSPEND'],
}

export interface UserInfo {
  id: string
  tenantId: string
  username: string
  email: string
  role: UserRole
  permissions?: Permission[]
  mustChangePassword?: boolean
}

// ─── Roles & RBAC ────────────────────────────────────────────────────────────

export interface RoleDefinitionDto {
  id: string
  tenantId?: string
  name: string
  description?: string
  builtIn: boolean
  permissions: Permission[]
  createdAt: string
}

export interface CreateRoleRequest {
  name: string
  description?: string
  permissions: Permission[]
}

export interface UpdateRoleRequest {
  name?: string
  description?: string
  permissions?: Permission[]
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

export interface TenantDto {
  id: string
  name: string
  slug: string
  status: TenantStatus
  plan: TenantPlan
  contactEmail?: string
  createdAt: string
}

// ─── Tenant Usage Analytics ─────────────────────────────────────────────────

export interface QuotaDimension {
  used: number
  limit: number
  percentage: number
}

export interface TenantUsageCurrent {
  tenantId: string
  plan: TenantPlan
  routes: QuotaDimension
  filters: QuotaDimension
  requests: QuotaDimension
  periodStart: string
  periodEnd: string
}

export interface DailyUsage {
  date: string
  routeCount: number
  filterCount: number
  requestCount: number
  errorCount: number
}

export interface TenantUsageHistory {
  tenantId: string
  entries: DailyUsage[]
}

// ─── Routes ───────────────────────────────────────────────────────────────────

export type RouteStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
export type RouteEnvironment = 'STAGING' | 'PRODUCTION'

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
  environment: RouteEnvironment
  version: number
  filters: RouteFilterRef[]
  extraConfig?: Record<string, unknown>
  createdBy?: string
  createdAt: string
  updatedAt: string
  activatedAt?: string
  trafficWeight: number
  canaryRouteId?: string
  canaryAutoRollbackThreshold?: number
}

export interface RouteSummary {
  id: string
  name: string
  description?: string
  pathPattern: string
  methods: string
  upstreamUri: string
  status: RouteStatus
  environment: RouteEnvironment
  version: number
  filterCount: number
  preFilterCount: number
  postFilterCount: number
  createdAt: string
  activatedAt?: string
  trafficWeight: number
  canaryRouteId?: string
}

export interface CreateRouteRequest {
  name: string
  description?: string
  pathPattern: string
  methods: string
  upstreamUri: string
  stripPrefix?: string
  extraConfig?: Record<string, unknown>
  environment?: RouteEnvironment
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

// ─── Canary Routing ───────────────────────────────────────────────────────────

export interface DeployCanaryRequest {
  canaryUpstreamUri: string
  trafficWeight: number
  autoRollbackThreshold: number
  canaryExtraConfig?: Record<string, unknown>
}

export interface AdjustCanaryWeightRequest {
  weight: number
}

export interface CanaryStatusResponse {
  routeId: string
  canaryRouteId: string
  primaryWeight: number
  canaryWeight: number
  canaryUpstreamUri: string
  autoRollbackThreshold: number
  primaryErrorRate: number
  canaryErrorRate: number
  deployedAt?: string
  breachCount: number
}

// ─── Filters ──────────────────────────────────────────────────────────────────

export type FilterType =
  | 'AUTH_API_KEY'
  | 'AUTH_BASIC'
  | 'AUTH_JWT'
  | 'AUTH_MTLS'
  | 'AUTH_OAUTH2'
  | 'AUTH_CLIENT_ID'
  | 'AUTH_CERT_VAULT'
  | 'DOWNSTREAM_BASIC_AUTH'
  | 'DOWNSTREAM_BEARER_CC'
  | 'RATE_LIMIT_FIXED_WINDOW'
  | 'RATE_LIMIT_SLIDING_WINDOW'
  | 'REQUEST_HEADER_MODIFY'
  | 'RESPONSE_HEADER_MODIFY'
  | 'BODY_JOLT_TRANSFORM'
  | 'VALIDATE_JSON_SCHEMA'
  | 'REQUEST_SIZE_LIMIT'
  | 'TIMEOUT'
  // ─── Resilience ─────────────────────────────────────────────────────────
  | 'CIRCUIT_BREAKER_V2'
  | 'RETRY_V2'
  // ─── Performance ───────────────────────────────────────────────────────
  | 'RESPONSE_CACHE'
  | 'CONDITIONAL_ROUTE'
  | 'USER_ID_PAYLOAD_ROUTING'
  | 'GEO_ROUTE'
  | 'CERT_ROTATION'
  | 'CERT_VAULT_EXPIRY_CHECK'
  | 'API_VERSIONING'
  | 'CORRELATION_ID'
  | 'REQUEST_LOGGER'
  | 'TENANT_CONTEXT'
  | 'SECURITY_HEADERS'
  | 'CUSTOM_METRIC'
  | 'BODY_SIZE_METRIC'
  | 'CUSTOM_SPEL'
  // ─── Security ─────────────────────────────────────────────────────────────
  | 'IP_ACCESS_CONTROL'
  // ─── AI ──────────────────────────────────────────────────────────────────
  | 'AI_FILTER'
  | 'AI_MODIFIER'

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
  roleId?: string
  roleName?: string
  permissions?: Permission[]
}

export interface CreateUserRequest {
  username: string
  email: string
  password: string
  role: UserRole
}

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

export interface GatewayTenantIsolationConfig {
  enabled: boolean
  tenantIdHeader: string
  allowCrossTenantsForSuperAdmin: boolean
}

/** A reference to a filter that has been marked as global (applied to all routes). */
export interface GlobalFilterEntry {
  filterId: string
  filterName: string
  filterType: FilterType
  order: number
  enabled: boolean
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
  globalFilterEntries: GlobalFilterEntry[]
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

// ─── ACME (Automated Certificate Lifecycle) ──────────────────────────────────

export type AcmeProvider = 'LETSENCRYPT' | 'ZEROSSSL'
export type AcmeOrderStatus = 'PENDING' | 'VALIDATING' | 'COMPLETED' | 'FAILED' | 'RENEWAL_FAILED'

export interface AcmeAccountDto {
  id: string
  tenantId: string
  email: string
  accountUrl?: string
  provider: AcmeProvider
  status: string
  createdAt: string
}

export interface AcmeOrderDto {
  id: string
  tenantId: string
  domain: string
  certGroupId?: string
  challengeType: string
  status: AcmeOrderStatus
  orderUrl?: string
  challengeToken?: string
  certId?: string
  autoRenew: boolean
  lastRenewedAt?: string
  nextRenewalAt?: string
  errorMessage?: string
  createdAt: string
  updatedAt?: string
}

export interface AcmeOrdersPage {
  content: AcmeOrderDto[]
  totalElements: number
  totalPages: number
  page: number
  size: number
  first: boolean
  last: boolean
}

export interface RegisterAcmeAccountRequest {
  email: string
  provider: AcmeProvider
}

export interface IssueAcmeCertificateRequest {
  accountId: string
  domain: string
  certGroupId?: string
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
  labels?: DecisionLabel[]
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

// ─── API Key Management ───────────────────────────────────────────────────────

export type ApiKeyStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED'

export interface ApiKeyDto {
  id: string
  tenantId: string
  name: string
  keyPrefix: string
  role: UserRole
  email?: string
  status: ApiKeyStatus
  expiresAt?: string
  lastUsedAt?: string
  createdAt: string
}

export interface ApiKeyDetailDto {
  id: string
  tenantId: string
  userId: string
  name: string
  keyPrefix: string
  role: string
  email?: string
  status: ApiKeyStatus
  expiresAt?: string
  lastUsedAt?: string
  createdBy?: string
  createdAt: string
  revokedAt?: string
}

export interface CreateApiKeyRequest {
  name: string
  role?: string
  email?: string
  expiresAt?: string
}

/** Returned by create and rotate — contains the raw key shown once. */
export interface ApiKeyCreatedResponse {
  id: string
  rawKey: string
  keyPrefix: string
  name: string
  role: string
  expiresAt?: string
  createdAt: string
}

// ─── Webhook Notifications ────────────────────────────────────────────────────

export type WebhookEventType =
  | 'ROUTE_CREATED'
  | 'ROUTE_ACTIVATED'
  | 'ROUTE_DEACTIVATED'
  | 'ROUTE_DELETED'
  | 'ROUTE_PROMOTED'
  | 'FILTER_CREATED'
  | 'FILTER_UPDATED'
  | 'FILTER_DELETED'
  | 'CERT_UPLOADED'
  | 'CERT_REVOKED'
  | 'CERT_EXPIRING'
  | 'CERT_EXPIRED'
  | 'USER_CREATED'
  | 'USER_DELETED'
  | 'TENANT_SUSPENDED'
  | 'TENANT_REACTIVATED'
  | 'AI_FILTER_BLOCKED'
  | 'AI_FILTER_FLAGGED'
  | 'DLQ_OVERFLOW'
  | 'GATEWAY_RELOAD_FAILED'
  | 'GATEWAY_CONFIG_DRIFT'
  | 'QUOTA_WARNING'
  | 'QUOTA_EXCEEDED'
  | 'CANARY_DEPLOYED'
  | 'CANARY_PROMOTED'
  | 'CANARY_ROLLBACK'

export type WebhookSubscriptionStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED'

export interface WebhookSubscriptionDto {
  id: string
  tenantId: string
  name: string
  url: string
  eventTypes: WebhookEventType[]
  status: WebhookSubscriptionStatus
  failureCount: number
  lastDeliveredAt?: string
  createdAt: string
  updatedAt: string
}

export interface WebhookDetailDto {
  id: string
  tenantId: string
  name: string
  url: string
  secret: string
  eventTypes: WebhookEventType[]
  status: WebhookSubscriptionStatus
  failureCount: number
  lastDeliveredAt?: string
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface CreateWebhookRequest {
  name: string
  url: string
  eventTypes: WebhookEventType[]
}

export interface UpdateWebhookRequest {
  name?: string
  url?: string
  eventTypes?: WebhookEventType[]
}

export type WebhookDeliveryStatus = 'PENDING' | 'DELIVERED' | 'FAILED'

export interface WebhookDeliveryDto {
  id: string
  subscriptionId: string
  eventType: string
  payload: string
  responseStatus?: number
  responseBody?: string
  attempt: number
  status: WebhookDeliveryStatus
  deliveredAt?: string
  nextRetryAt?: string
  errorMessage?: string
  createdAt: string
}

export interface WebhookTestResult {
  success: boolean
  responseStatus?: number
  message: string
}

// ─── Gateway Health Dashboard v2 ──────────────────────────────────────────────

export interface RouteHealthEntry {
  routeId: string
  routeName: string
  totalRequests: number
  errorCount: number
  errorRate: number
  p50LatencyMs: number
  p95LatencyMs: number
  p99LatencyMs: number
  avgLatencyMs: number
  statusCodeDistribution: Record<number, number>
}

export interface RouteHealthResponse {
  routes: RouteHealthEntry[]
}

export type HealthTimeWindow = '1h' | '24h' | '7d'

export interface RouteSloConfig {
  availabilityTarget: number
  latencyP99TargetMs: number
  evaluationWindowHours: number
  configured?: boolean
}

export interface SloStatus {
  routeId: string
  slo: RouteSloConfig & { configured: boolean }
  actual: {
    availability: number
    latencyP99Ms: number
    totalRequests: number
    errorCount: number
  }
  errorBudget: {
    totalBudget: number
    consumed: number
    remaining: number
    percentConsumed: number
  }
  latencySloMet: boolean
  availabilitySloMet: boolean
}

// ─── Route Import / Export (GitOps) ───────────────────────────────────────────

export interface ImportPreviewResponse {
  valid: boolean
  changes: DiffSections
  warnings: string[]
}

export interface DiffSections {
  filters: DiffSection
  routes: DiffSection
}

export interface DiffSection {
  create: DiffCreateEntry[]
  update: DiffUpdateEntry[]
  unchanged: string[]
  delete: string[]
}

export interface DiffCreateEntry {
  name: string
  type: string
}

export interface DiffUpdateEntry {
  name: string
  changes: string[]
}

// ─── GitOps ───────────────────────────────────────────────────────────────────

export type ReconciliationOutcome = 'APPLIED' | 'DRIFT_DETECTED' | 'FAILED' | 'NO_CHANGE'

export interface ReconciliationResult {
  timestamp: string
  commitHash: string | null
  configHash: string | null
  outcome: ReconciliationOutcome
  routesCreated: number
  routesUpdated: number
  filtersCreated: number
  filtersUpdated: number
  warnings: string[]
  errorMessage: string | null
}

export interface GitOpsStatus {
  enabled: boolean
  repositoryUrl: string
  branch: string
  configPath: string
  pollIntervalSeconds: number
  dryRun: boolean
  tenantId: string
  lastAppliedHash: string | null
  lastCommitHash: string | null
  lastSyncTime: string | null
  lastOutcome: ReconciliationOutcome | null
}

// ─── Multi-Gateway Fleet Status ───────────────────────────────────────────────

export type GatewayInstanceStatus = 'HEALTHY' | 'STALE' | 'UNRESPONSIVE'

export interface GatewayInstanceInfo {
  instanceId: string
  hostname: string
  configVersion: number
  routeCount: number
  filterCount: number
  status: GatewayInstanceStatus
  startedAt: string
  lastReloadAt: string
  lastHeartbeatAt: string
  uptimeHours: number
}

export interface FleetStatusResponse {
  globalConfigVersion: number
  instanceCount: number
  healthyCount: number
  staleCount: number
  instances: GatewayInstanceInfo[]
}

// ─── AI Prompt Versioning ─────────────────────────────────────────────────────

export type PromptVersionStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'

export interface AiPromptVersion {
  id: string
  filterId: string
  tenantId: string
  version: number
  promptText: string
  description?: string
  status: PromptVersionStatus
  accuracyScore?: number
  totalDecisions: number
  correctCount: number
  createdBy?: string
  createdAt: string
  activatedAt?: string
  archivedAt?: string
}

export interface AiPromptVersionSummary {
  id: string
  filterId: string
  version: number
  status: PromptVersionStatus
  description?: string
  accuracyScore?: number
  totalDecisions: number
  createdAt: string
  activatedAt?: string
}

export type DecisionLabel = 'CORRECT' | 'INCORRECT' | 'UNCLEAR'

export interface AiDecisionLabelResult {
  success: boolean
  promptVersionId?: string
  newAccuracy?: number
}

// ─── Alerting Engine (Initiative 15) ────────────────────────────────────────

export type AlertMetric =
  | 'ERROR_RATE'
  | 'P99_LATENCY'
  | 'DLQ_DEPTH'
  | 'CERT_EXPIRY_DAYS'
  | 'QUOTA_USAGE'
  | 'SLO_BUDGET'
  | 'REQUEST_VOLUME'
  | 'AUTH_FAILURE_RATE'

export type AlertOperator = 'GT' | 'LT' | 'GTE' | 'LTE' | 'EQ'

export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL'

export type AlertState = 'OK' | 'PENDING' | 'FIRING'

export interface AlertRule {
  id: string
  tenantId: string
  name: string
  description?: string
  metric: AlertMetric
  routeId?: string
  operator: AlertOperator
  threshold: number
  windowMinutes: number
  cooldownMinutes: number
  severity: AlertSeverity
  enabled: boolean
  currentState: AlertState
  stateChangedAt?: string
  consecutiveBreaches: number
  lastEvaluatedAt?: string
  lastFiredAt?: string
  mutedUntil?: string
  createdBy?: string
  createdAt: string
  updatedAt?: string
}

export interface AlertEvent {
  id: string
  ruleId: string
  tenantId: string
  transition: string
  metricValue?: number
  threshold?: number
  message?: string
  occurredAt: string
}

export interface CreateAlertRuleRequest {
  name: string
  description?: string
  metric: AlertMetric
  routeId?: string
  operator: AlertOperator
  threshold: number
  windowMinutes?: number
  cooldownMinutes?: number
  severity?: AlertSeverity
  enabled?: boolean
}

export interface UpdateAlertRuleRequest {
  name?: string
  description?: string
  metric?: AlertMetric
  routeId?: string
  operator?: AlertOperator
  threshold?: number
  windowMinutes?: number
  cooldownMinutes?: number
  severity?: AlertSeverity
  enabled?: boolean
}

export interface MuteAlertRequest {
  durationMinutes: number
}
