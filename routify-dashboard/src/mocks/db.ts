/**
 * db.ts — In-memory mock database shared across all MSW handlers.
 *
 * All collections are mutable Maps so create/update/delete operations
 * from one handler are immediately visible to list/get handlers in
 * the same session. Data resets on every page reload (browser refresh).
 */
import type {
  RouteDto, FilterDefinitionDto, UserDto, TenantDto,
  CertificateDto, CertGroupDto,
  GatewayConfig, GatewayLiveStatus,
} from '../types'

import { seedRoutes }      from './data/routes'
import { seedFilters }     from './data/filters'
import { seedUsers }       from './data/users'
import { seedTenants, MOCK_TENANT_ID }     from './data/tenants'
import { seedCerts, seedCertGroups }       from './data/certs'
import { seedAuditEvents, seedRequestLogs, seedFailedRequests, seedReplayStats } from './data/audit'
import type { AuditEntry, RequestLogDto, FailedRequestDto, ReplayStats } from '../types'

// ─── Generic helpers ──────────────────────────────────────────────────────────

/** Build a page response matching the Routify Page<T> contract */
export function buildPage<T>(items: T[], page = 0, size = 20) {
  const totalElements = items.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  const clampedPage = Math.min(page, totalPages - 1)
  const content = items.slice(clampedPage * size, clampedPage * size + size)
  return {
    content,
    totalElements,
    totalPages,
    size,
    page: clampedPage,
    first: clampedPage === 0,
    last:  clampedPage >= totalPages - 1,
  }
}

// ─── Routes ───────────────────────────────────────────────────────────────────

export const routes = new Map<string, RouteDto>(
  seedRoutes.map(r => [r.id, { ...r }])
)

// ─── Filters ─────────────────────────────────────────────────────────────────

export const filters = new Map<string, FilterDefinitionDto>(
  seedFilters.map(f => [f.id, { ...f }])
)

// ─── Users ────────────────────────────────────────────────────────────────────

export const users = new Map<string, UserDto>(
  seedUsers.map(u => [u.id, { ...u }])
)

// ─── Tenants ─────────────────────────────────────────────────────────────────

export const tenants = new Map<string, TenantDto>(
  seedTenants.map(t => [t.id, { ...t }])
)

// ─── Certificates ─────────────────────────────────────────────────────────────

export const certs = new Map<string, CertificateDto>(
  seedCerts.map(c => [c.id, { ...c }])
)

export const certGroups = new Map<string, CertGroupDto>(
  seedCertGroups.map(g => [g.id, { ...g }])
)

// ─── Audit ────────────────────────────────────────────────────────────────────

export const auditEvents: AuditEntry[]       = [...seedAuditEvents]
export const requestLogs: RequestLogDto[]    = [...seedRequestLogs]
export const failedRequests: FailedRequestDto[] = [...seedFailedRequests]
export const replayStats: ReplayStats = { ...seedReplayStats }

// ─── Gateway config (mutable) ─────────────────────────────────────────────────

export const gatewayConfig: GatewayConfig = {
  updatedAt: '2026-04-01T10:00:00Z',
  updatedBy: 'admin',
  cors: {
    enabled: true,
    allowedOriginPatterns: ['http://localhost:*', 'https://*.routify.demo'],
    allowedMethods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['*'],
    exposedHeaders: ['X-Correlation-Id', 'X-Request-Id'],
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
    referrerPolicy: 'no-referrer',
    permissionsPolicy: 'camera=(), microphone=(), geolocation=()',
    removeServerHeader: true,
    removePoweredByHeader: true,
  },
  rateLimitPolicies: [
    {
      id: 'rl-100rpm',
      name: '100 req/min',
      description: 'Standard rate limit for public endpoints',
      algorithm: 'FIXED_WINDOW',
      keyResolver: 'IP',
      replenishRate: 100,
      burstCapacity: 150,
      requestedTokens: 1,
      windowMs: 60000,
      enabled: true,
    },
    {
      id: 'rl-1000rpm',
      name: '1000 req/min',
      description: 'High-throughput limit for internal services',
      algorithm: 'TOKEN_BUCKET',
      keyResolver: 'TENANT',
      replenishRate: 1000,
      burstCapacity: 1200,
      requestedTokens: 1,
      windowMs: 60000,
      enabled: true,
    },
  ],
  circuitBreakerDefaults: {
    slidingWindowType: 'COUNT_BASED',
    slidingWindowSize: 10,
    minimumNumberOfCalls: 5,
    failureRateThreshold: 50,
    slowCallRateThreshold: 80,
    slowCallDurationThresholdMs: 3000,
    waitDurationInOpenState: '30s',
    permittedNumberOfCallsInHalfOpenState: 3,
    automaticTransitionFromOpenToHalfOpen: true,
    fallbackUri: '/fallback',
    recordExceptions: true,
  },
  resilienceDefaults: {
    retryMaxAttempts: 3,
    retryWaitDuration: '500ms',
    retryExponentialBackoff: true,
    retryExponentialMultiplier: 1.5,
    retryMaxWaitDuration: '5s',
    timeoutDuration: '30s',
    timeoutCancelRunningFuture: true,
    bulkheadEnabled: false,
    bulkheadMaxConcurrentCalls: 100,
    bulkheadMaxWaitDuration: 0,
  },
  authProviders: [
    {
      id:        'ap-jwt-01',
      name:      'JWT RS256 Provider',
      type:      'JWT_VERIFY',
      enabled:   true,
      issuer:    'https://routify.demo',
      audience:  'routify-dashboard',
      algorithm: 'RS256',
      jwksUri:   'http://identity-service:8083/.well-known/jwks.json',
    },
    {
      id:           'ap-apikey-01',
      name:         'API Key Provider',
      type:         'BASIC',
      enabled:      true,
      parameterName: 'X-Api-Key',
      parameterStyle: 'HEADER',
    },
  ],
  // TLS is now managed exclusively by Cert Vault — no deprecated file-source fields
  tlsConfig: {},
  proxyConfig: {
    enabled: false,
    nonProxyHosts: ['localhost', '127.0.0.1'],
    type: 'HTTP',
  },
  httpClientConfig: {
    connectTimeoutMs: 5000,
    responseTimeoutMs: 30000,
    maxConnections: 500,
    maxConnectionsPerRoute: 50,
    acquireTimeoutMs: 60000,
    maxIdleTime: '60s',
    maxLifeTime: '300s',
    compressionEnabled: true,
    followRedirects: true,
    wiretapEnabled: false,
  },
  tenantIsolation: {
    enabled: true,
    enforceHeaderPredicate: true,
    tenantIdHeader: 'X-Tenant-Id',
    allowCrossTenantsForSuperAdmin: true,
  },
}

export const gatewayLiveStatus: GatewayLiveStatus = {
  health: {
    status: 'UP',
    components: {
      redis:    { status: 'UP' },
      kafka:    { status: 'UP' },
      rabbitmq: { status: 'UP' },
      db:       { status: 'UP' },
    },
  },
  routes: {
    count: seedRoutes.filter(r => r.status === 'ACTIVE').length,
    routes: seedRoutes.filter(r => r.status === 'ACTIVE').map(r => ({ id: r.id, name: r.name, path: r.pathPattern })),
  },
  circuitBreakers: {
    'payments-cb': { state: 'CLOSED', failureRate: 2.1, slowCallRate: 0, bufferedCalls: 10 },
    'users-cb':    { state: 'CLOSED', failureRate: 0,   slowCallRate: 5, bufferedCalls: 8  },
    'orders-cb':   { state: 'HALF_OPEN', failureRate: 55, slowCallRate: 20, bufferedCalls: 3  },
  },
}

export { MOCK_TENANT_ID }

