/**
 * In-memory mock database.
 * All handlers read/write from this single object so mutations (create, update,
 * delete, activate, detach …) are fully reactive across the mock layer.
 */

import type {
  RouteDto, RouteSummary, FilterDefinitionDto, FilterSummary,
  UserDto, AuditEntry, RequestLogDto, GatewayConfig, FailedRequestDto, ReplayStatus,
  TenantDto,
} from '../types'

// ─── helpers ─────────────────────────────────────────────────────────────────

let _seq = 1000
export const nextId = () => `mock-${++_seq}`

export const now = () => new Date().toISOString()
export const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString()
export const rnd = (min: number, max: number) => Math.floor(Math.random() * (max - min + 1)) + min

// ─── Tenants (workspaces) ─────────────────────────────────────────────────────

export const tenants: TenantDto[] = [
  {
    id: 'ten-platform',
    name: 'Routify',
    slug: 'routify',
    status: 'ACTIVE',
    plan: 'ENTERPRISE',
    contactEmail: 'platform@routify.dev',
    createdAt: daysAgo(365),
  },
  {
    id: 'ten-acme',
    name: 'Acme Corp',
    slug: 'acme',
    status: 'ACTIVE',
    plan: 'PRO',
    contactEmail: 'admin@acme.example',
    createdAt: daysAgo(120),
  },
  {
    id: 'ten-staging',
    name: 'Staging',
    slug: 'staging',
    status: 'ACTIVE',
    plan: 'STARTER',
    contactEmail: 'ops@routify.dev',
    createdAt: daysAgo(60),
  },
]

// ─── Filters ─────────────────────────────────────────────────────────────────

export const filters: FilterDefinitionDto[] = [
  {
    id: 'flt-001',
    tenantId: 'ten-platform',
    name: 'JWT Auth',
    description: 'Validates Bearer JWT tokens using the platform RS256 key.',
    filterType: 'AUTH_JWT',
    config: { issuer: 'https://platform.auth', audience: 'api://routify', algorithm: 'RS256' },
    systemManaged: false,
    enabled: true,
    usageCount: 3,
    createdBy: 'admin',
    createdAt: daysAgo(20),
    updatedAt: daysAgo(5),
  },
  {
    id: 'flt-002',
    tenantId: 'ten-platform',
    name: 'Rate Limit – 100/min',
    description: 'Fixed-window rate limiter: 100 requests per minute per IP.',
    filterType: 'RATE_LIMIT_FIXED_WINDOW',
    config: { maxRequests: 100, windowMs: 60_000, keyResolver: 'IP' },
    systemManaged: false,
    enabled: true,
    usageCount: 2,
    createdBy: 'admin',
    createdAt: daysAgo(18),
    updatedAt: daysAgo(18),
  },
  {
    id: 'flt-003',
    tenantId: 'ten-platform',
    name: 'Add Correlation-ID',
    description: 'Injects X-Correlation-Id header into every upstream request.',
    filterType: 'CORRELATION_ID',
    config: {},
    systemManaged: false,
    enabled: true,
    usageCount: 5,
    createdBy: 'admin',
    createdAt: daysAgo(15),
    updatedAt: daysAgo(15),
  },
  {
    id: 'flt-004',
    tenantId: 'ten-platform',
    name: 'Response Header – Remove Server',
    description: 'Strips the Server and X-Powered-By response headers.',
    filterType: 'RESPONSE_HEADER_MODIFY',
    config: { add: {}, set: {}, remove: { 'Server': '', 'X-Powered-By': '' } },
    systemManaged: false,
    enabled: true,
    usageCount: 4,
    createdBy: 'admin',
    createdAt: daysAgo(12),
    updatedAt: daysAgo(12),
  },
  {
    id: 'flt-005',
    tenantId: 'ten-platform',
    name: 'Request Timeout – 10 s',
    description: 'Returns 504 if the upstream does not respond within 10 seconds.',
    filterType: 'TIMEOUT',
    config: { timeoutMs: 10_000 },
    systemManaged: false,
    enabled: true,
    usageCount: 1,
    createdBy: 'admin',
    createdAt: daysAgo(8),
    updatedAt: daysAgo(8),
  },
  {
    id: 'flt-006',
    tenantId: 'ten-platform',
    name: 'Request Logger',
    description: 'Logs request/response metadata and publishes telemetry to Kafka.',
    filterType: 'REQUEST_LOGGER',
    config: { logRequestHeaders: true, logResponseHeaders: true, logRequestBody: false, logResponseBody: false, maxBodyLogSize: 4096, failedStatusThreshold: 500 },
    systemManaged: true,
    enabled: true,
    usageCount: 6,
    createdBy: 'system',
    createdAt: daysAgo(30),
    updatedAt: daysAgo(30),
  },
  {
    id: 'flt-007',
    tenantId: 'ten-platform',
    name: 'Sliding Window – 500/min',
    description: 'Sliding-window rate limiter: 500 requests per minute per user.',
    filterType: 'RATE_LIMIT_SLIDING_WINDOW',
    config: { maxRequests: 500, windowMs: 60_000, keyResolver: 'USER' },
    systemManaged: false,
    enabled: false,
    usageCount: 0,
    createdBy: 'admin',
    createdAt: daysAgo(3),
    updatedAt: daysAgo(3),
  },
]

// ─── Routes ───────────────────────────────────────────────────────────────────

export const routes: RouteDto[] = [
  {
    id: 'rte-001',
    tenantId: 'ten-platform',
    name: 'User Service',
    description: 'Proxies all /api/v1/users/** calls to the internal user microservice.',
    pathPattern: '/api/v1/users/**',
    methods: 'GET,POST,PUT,DELETE',
    upstreamUri: 'http://user-service:8081',
    stripPrefix: '/api/v1',
    status: 'ACTIVE',
    version: 3,
    filters: [
      { filterId: 'flt-001', filterName: 'JWT Auth',         filterType: 'AUTH_JWT',            order: 1, phase: 'PRE',  enabled: true },
      { filterId: 'flt-002', filterName: 'Rate Limit – 100/min', filterType: 'RATE_LIMIT_FIXED_WINDOW', order: 2, phase: 'PRE', enabled: true },
      { filterId: 'flt-003', filterName: 'Add Correlation-ID', filterType: 'CORRELATION_ID',    order: 3, phase: 'PRE',  enabled: true },
    ],
    createdBy: 'admin',
    createdAt: daysAgo(25),
    updatedAt: daysAgo(2),
    activatedAt: daysAgo(2),
  },
  {
    id: 'rte-002',
    tenantId: 'ten-platform',
    name: 'Order Service',
    description: 'Routes /api/v1/orders to the order microservice with timeout protection.',
    pathPattern: '/api/v1/orders/**',
    methods: 'GET,POST',
    upstreamUri: 'http://order-service:8082',
    stripPrefix: '/api/v1',
    status: 'ACTIVE',
    version: 2,
    filters: [
      { filterId: 'flt-001', filterName: 'JWT Auth',        filterType: 'AUTH_JWT',  order: 1, phase: 'PRE', enabled: true },
      { filterId: 'flt-005', filterName: 'Request Timeout – 10 s', filterType: 'TIMEOUT', order: 2, phase: 'PRE', enabled: true },
    ],
    createdBy: 'admin',
    createdAt: daysAgo(20),
    updatedAt: daysAgo(4),
    activatedAt: daysAgo(4),
  },
  {
    id: 'rte-003',
    tenantId: 'ten-platform',
    name: 'Product Catalog',
    description: 'Read-only proxy for product data. No auth — public endpoint.',
    pathPattern: '/api/v1/products/**',
    methods: 'GET',
    upstreamUri: 'http://catalog-service:8083',
    status: 'ACTIVE',
    version: 1,
    filters: [
      { filterId: 'flt-002', filterName: 'Rate Limit – 100/min', filterType: 'RATE_LIMIT_FIXED_WINDOW', order: 1, phase: 'PRE', enabled: true },
    ],
    createdBy: 'admin',
    createdAt: daysAgo(18),
    updatedAt: daysAgo(18),
    activatedAt: daysAgo(18),
  },
  {
    id: 'rte-004',
    tenantId: 'ten-platform',
    name: 'Notification Service',
    description: 'Internal notifications endpoint — draft, pending review.',
    pathPattern: '/api/v1/notifications/**',
    methods: 'POST',
    upstreamUri: 'http://notification-service:8084',
    status: 'DRAFT',
    version: 1,
    filters: [],
    createdBy: 'operator1',
    createdAt: daysAgo(5),
    updatedAt: daysAgo(5),
  },
  {
    id: 'rte-005',
    tenantId: 'ten-platform',
    name: 'Analytics Ingest',
    description: 'Bulk event ingestion pipeline. Disabled while data-schema migration runs.',
    pathPattern: '/api/v1/analytics/events',
    methods: 'POST',
    upstreamUri: 'http://analytics-service:8085',
    status: 'DISABLED',
    version: 2,
    filters: [
      { filterId: 'flt-006', filterName: 'Request Logger', filterType: 'REQUEST_LOGGER', order: 1, phase: 'PRE', enabled: true },
    ],
    createdBy: 'admin',
    createdAt: daysAgo(30),
    updatedAt: daysAgo(1),
    activatedAt: daysAgo(10),
  },
  {
    id: 'rte-006',
    tenantId: 'ten-platform',
    name: 'Legacy Auth v1',
    description: 'Old auth endpoint — archived after OAuth2 migration.',
    pathPattern: '/api/v1/auth/legacy/**',
    methods: 'GET,POST',
    upstreamUri: 'http://legacy-auth:8086',
    status: 'ARCHIVED',
    version: 5,
    filters: [],
    createdBy: 'admin',
    createdAt: daysAgo(90),
    updatedAt: daysAgo(30),
    activatedAt: daysAgo(60),
  },
]

// ─── Users ────────────────────────────────────────────────────────────────────

export const users: UserDto[] = [
  {
    id: 'usr-001',
    tenantId: 'ten-platform',
    username: 'admin',
    email: 'admin@routify.dev',
    role: 'SUPER_ADMIN',
    status: 'ACTIVE',
    lastLoginAt: now(),
    createdAt: daysAgo(90),
  },
  {
    id: 'usr-002',
    tenantId: 'ten-platform',
    username: 'alice',
    email: 'alice@routify.dev',
    role: 'TENANT_ADMIN',
    status: 'ACTIVE',
    lastLoginAt: daysAgo(1),
    createdAt: daysAgo(60),
  },
  {
    id: 'usr-003',
    tenantId: 'ten-platform',
    username: 'bob',
    email: 'bob@routify.dev',
    role: 'OPERATOR',
    status: 'ACTIVE',
    lastLoginAt: daysAgo(3),
    createdAt: daysAgo(45),
  },
  {
    id: 'usr-004',
    tenantId: 'ten-platform',
    username: 'charlie',
    email: 'charlie@routify.dev',
    role: 'VIEWER',
    status: 'ACTIVE',
    lastLoginAt: daysAgo(7),
    createdAt: daysAgo(30),
  },
  {
    id: 'usr-005',
    tenantId: 'ten-platform',
    username: 'diana',
    email: 'diana@routify.dev',
    role: 'OPERATOR',
    status: 'ACTIVE',
    lastLoginAt: daysAgo(2),
    createdAt: daysAgo(14),
  },
]

// ─── Audit events ─────────────────────────────────────────────────────────────

export const auditEvents: AuditEntry[] = [
  {
    eventId: 'evt-001',
    tenantId: 'ten-platform',
    eventType: 'ROUTE_ACTIVATED',
    aggregateType: 'Route',
    aggregateId: 'rte-001',
    actorId: 'usr-001',
    correlationId: 'corr-aaa111',
    occurredAt: daysAgo(2),
    recordedAt: daysAgo(2),
  },
  {
    eventId: 'evt-002',
    tenantId: 'ten-platform',
    eventType: 'FILTER_ATTACHED',
    aggregateType: 'Route',
    aggregateId: 'rte-001',
    actorId: 'usr-001',
    correlationId: 'corr-bbb222',
    occurredAt: daysAgo(2),
    recordedAt: daysAgo(2),
  },
  {
    eventId: 'evt-003',
    tenantId: 'ten-platform',
    eventType: 'ROUTE_CREATED',
    aggregateType: 'Route',
    aggregateId: 'rte-004',
    actorId: 'usr-003',
    correlationId: 'corr-ccc333',
    occurredAt: daysAgo(5),
    recordedAt: daysAgo(5),
  },
  {
    eventId: 'evt-004',
    tenantId: 'ten-platform',
    eventType: 'ROUTE_DEACTIVATED',
    aggregateType: 'Route',
    aggregateId: 'rte-005',
    actorId: 'usr-001',
    correlationId: 'corr-ddd444',
    occurredAt: daysAgo(1),
    recordedAt: daysAgo(1),
  },
  {
    eventId: 'evt-005',
    tenantId: 'ten-platform',
    eventType: 'USER_CREATED',
    aggregateType: 'User',
    aggregateId: 'usr-005',
    actorId: 'usr-001',
    correlationId: 'corr-eee555',
    occurredAt: daysAgo(14),
    recordedAt: daysAgo(14),
  },
  {
    eventId: 'evt-006',
    tenantId: 'ten-platform',
    eventType: 'FILTER_CREATED',
    aggregateType: 'Filter',
    aggregateId: 'flt-007',
    actorId: 'usr-002',
    correlationId: 'corr-fff666',
    occurredAt: daysAgo(3),
    recordedAt: daysAgo(3),
  },
  {
    eventId: 'evt-007',
    tenantId: 'ten-platform',
    eventType: 'ROUTE_UPDATED',
    aggregateType: 'Route',
    aggregateId: 'rte-002',
    actorId: 'usr-002',
    correlationId: 'corr-ggg777',
    occurredAt: daysAgo(4),
    recordedAt: daysAgo(4),
  },
  {
    eventId: 'evt-008',
    tenantId: 'ten-platform',
    eventType: 'GATEWAY_RELOADED',
    aggregateType: 'Gateway',
    aggregateId: 'gw-main',
    actorId: undefined,
    correlationId: 'corr-hhh888',
    occurredAt: daysAgo(2),
    recordedAt: daysAgo(2),
  },
]

// ─── Gateway Configuration ────────────────────────────────────────────────────

export const gatewayConfig: GatewayConfig = {
  updatedAt: daysAgo(1),
  updatedBy: 'admin',  cors: {
    enabled: true,
    allowedOriginPatterns: ['http://localhost:5173', 'https://app.routify.dev'],
    allowedMethods: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS'],
    allowedHeaders: ['*'],
    exposedHeaders: ['X-Correlation-Id', 'X-Route-Version'],
    allowCredentials: true,
    maxAge: 3600,
    paths: ['/**'],
  },
  securityHeaders: {
    enabled: true,
    xContentTypeOptions: true,
    xFrameOptions: true,
    xFrameOptionsValue: 'DENY',
    xXssProtection: true,
    strictTransportSecurity: true,
    stsMaxAge: 31536000,
    stsIncludeSubDomains: true,
    stsPreload: false,
    referrerPolicy: 'strict-origin-when-cross-origin',
    permissionsPolicy: 'geolocation=(), camera=(), microphone=()',
    removeServerHeader: true,
    removePoweredByHeader: true,
    customHeaders: {},
  },
  rateLimitPolicies: [
    {
      id: 'rl-001',
      name: 'Auth Endpoints — 20/s',
      description: 'Token bucket rate limit on /api/v1/auth/** — 20 req/s, burst 40',
      algorithm: 'TOKEN_BUCKET',
      keyResolver: 'IP',
      replenishRate: 20,
      burstCapacity: 40,
      requestedTokens: 1,
      windowMs: 1000,
      enabled: true,
      globalPaths: ['/api/v1/auth/**'],
    },
    {
      id: 'rl-002',
      name: 'API General — 100/min',
      description: 'Fixed window: 100 requests per minute per IP for all /api/** routes',
      algorithm: 'FIXED_WINDOW',
      keyResolver: 'IP',
      replenishRate: 100,
      burstCapacity: 120,
      requestedTokens: 1,
      windowMs: 60000,
      enabled: false,
      globalPaths: ['/api/**'],
    },
  ],
  circuitBreakerDefaults: {
    slidingWindowType: 'COUNT_BASED',
    slidingWindowSize: 100,
    minimumNumberOfCalls: 10,
    failureRateThreshold: 50,
    slowCallRateThreshold: 100,
    slowCallDurationThresholdMs: 60000,
    waitDurationInOpenState: '10s',
    permittedNumberOfCallsInHalfOpenState: 3,
    automaticTransitionFromOpenToHalfOpen: true,
    fallbackUri: 'forward:/fallback/503',
    recordExceptions: true,
    recordExceptionClasses: ['java.io.IOException', 'java.util.concurrent.TimeoutException'],
    ignoreExceptionClasses: [],
  },
  resilienceDefaults: {
    retryMaxAttempts: 3,
    retryWaitDuration: '500ms',
    retryExponentialBackoff: true,
    retryExponentialMultiplier: 2.0,
    retryMaxWaitDuration: '2s',
    retryExceptions: ['java.io.IOException'],
    timeoutDuration: '10s',
    timeoutCancelRunningFuture: true,
    bulkheadEnabled: false,
    bulkheadMaxConcurrentCalls: 25,
    bulkheadMaxWaitDuration: 500,
  },
  authProviders: [
    {
      id: 'ap-001',
      name: 'Platform Identity (JWT)',
      type: 'JWT_VERIFY',
      jwksUri: 'http://identity-service:8083/.well-known/jwks.json',
      issuer: 'routify-identity',
      audience: 'routify-gateway',
      algorithm: 'RS256',
      enabled: true,
    },
    {
      id: 'ap-002',
      name: 'External OAuth2 Introspect',
      type: 'OAUTH2_INTROSPECT',
      uri: 'https://auth.example.com/oauth2/introspect',
      clientId: 'gateway-client',
      clientSecret: '••••••••',
      parameterStyle: 'BODY',
      parameterName: 'token',
      enabled: false,
    },
  ],
  tlsConfig: {
    expiryWarning: '30d',
    fileWatchInterval: '30s',
    fileSources: [
      {
        logicalId: 'platform-cert',
        certificatePath: '/etc/routify/certs/platform.cer',
        watchForChanges: true,
        status: 'VALID',
        expiresAt: new Date(Date.now() + 90 * 86400000).toISOString(),
      },
    ],
    directorySources: [],
  },
  proxyConfig: {
    enabled: false,
    type: 'HTTP',
    nonProxyHosts: ['localhost', '127.0.0.1', '*.internal'],
  },
  httpClientConfig: {
    connectTimeoutMs: 6000,
    responseTimeoutMs: 10000,
    maxConnections: 500,
    maxConnectionsPerRoute: 50,
    acquireTimeoutMs: 45000,
    maxIdleTime: '20s',
    maxLifeTime: '60s',
    compressionEnabled: false,
    followRedirects: false,
    wiretapEnabled: false,
  },
  tenantIsolation: {
    enabled: true,
    enforceHeaderPredicate: true,
    tenantIdHeader: 'X-Tenant-Id',
    allowCrossTenantsForSuperAdmin: true,
  },
}

// ─── Request logs ─────────────────────────────────────────────────────────────

const METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'GET', 'GET', 'GET', 'POST']
const STATUS_CODES = [200, 200, 200, 200, 201, 201, 400, 401, 404, 500, 500, 503]
const UPSTREAM_URIS = [
  'http://user-service:8081/users/42',
  'http://order-service:8082/orders',
  'http://inventory-service:8083/products/7',
  'http://analytics-service:8085/events',
  'http://payment-service:8086/charge',
]

export const requestLogs: RequestLogDto[] = Array.from({ length: 80 }, (_, i) => {
  const routeRef = routes[i % routes.length]
  const status   = STATUS_CODES[rnd(0, STATUS_CODES.length - 1)]
  const failed   = status >= 500
  const replayStatuses: ReplayStatus[] = ['PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED']
  const replayStatus: ReplayStatus | undefined = failed
    ? replayStatuses[i % replayStatuses.length]
    : undefined
  return {
    id: `req-${String(i + 1).padStart(4, '0')}`,
    tenantId: 'ten-platform',
    routeId: routeRef.id,
    routeName: routeRef.name,
    correlationId: `corr-req-${i}`,
    method: METHODS[i % METHODS.length],
    path: routeRef.pathPattern.replace('/**', `/${i}`),
    queryString: i % 3 === 0 ? `page=${i % 5}&size=20` : undefined,
    upstreamUri: UPSTREAM_URIS[i % UPSTREAM_URIS.length],
    responseStatus: status,
    durationMs: rnd(12, 1850),
    clientIp: `10.0.${rnd(0, 5)}.${rnd(1, 254)}`,
    errorMessage: failed ? ['Upstream connection refused', 'Read timeout after 30s', 'Circuit breaker open — upstream unhealthy', 'Internal server error'][i % 4] : undefined,
    requestedAt: new Date(Date.now() - i * 90_000).toISOString(),
    failed,
    replayStatus,
    replayCount: failed ? (replayStatus === 'PENDING' ? 0 : rnd(1, 3)) : 0,
    replayedAt: replayStatus && replayStatus !== 'PENDING' ? new Date(Date.now() - i * 60_000).toISOString() : undefined,
    replayResponseStatus: replayStatus === 'SUCCEEDED' ? 200 : undefined,
    replayError: replayStatus === 'FAILED' ? 'Upstream still returning 503' : undefined,
  }
})

// ─── Failed requests store (mutable for replay simulation) ───────────────────

export const failedRequests: FailedRequestDto[] = requestLogs
  .filter(r => r.failed)
  .map(r => ({
    id: r.id,
    tenantId: r.tenantId,
    routeId: r.routeId,
    routeName: r.routeName,
    correlationId: r.correlationId,
    method: r.method,
    path: r.path,
    queryString: r.queryString,
    upstreamUri: r.upstreamUri,
    responseStatus: r.responseStatus,
    durationMs: r.durationMs,
    errorMessage: r.errorMessage,
    requestedAt: r.requestedAt,
    failed: true,
    replayStatus: r.replayStatus,
    replayCount: r.replayCount,
    replayedAt: r.replayedAt,
    replayResponseStatus: r.replayResponseStatus,
    replayError: r.replayError,
  }))

// ─── Helper: build a page ─────────────────────────────────────────────────────

export function buildPage<T>(
  items: T[],
  page = 0,
  size = 20,
) {
  const totalElements = items.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  const safePage = Math.min(page, totalPages - 1)
  const start = safePage * size
  const content = items.slice(start, start + size)
  return {
    content,
    totalElements,
    totalPages,
    size,
    page: safePage,
    first: safePage === 0,
    last: safePage >= totalPages - 1,
  }
}

// ─── Helper: route → summary ──────────────────────────────────────────────────

export function toRouteSummary(r: RouteDto): RouteSummary {
  return {
    id: r.id,
    name: r.name,
    description: r.description,
    pathPattern: r.pathPattern,
    methods: r.methods,
    upstreamUri: r.upstreamUri,
    status: r.status,
    version: r.version,
    filterCount: r.filters.length,
    preFilterCount: r.filters.filter(f => f.phase === 'PRE').length,
    postFilterCount: r.filters.filter(f => f.phase === 'POST').length,
    createdAt: r.createdAt,
    activatedAt: r.activatedAt,
  }
}

// ─── Helper: filter → summary ─────────────────────────────────────────────────

export function toFilterSummary(f: FilterDefinitionDto): FilterSummary {
  return {
    id: f.id,
    name: f.name,
    filterType: f.filterType,
    enabled: f.enabled,
    usageCount: f.usageCount,
    createdAt: f.createdAt,
  }
}

