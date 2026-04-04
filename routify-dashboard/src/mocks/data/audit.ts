import type { AuditEntry, RequestLogDto, FailedRequestDto, ReplayStats } from '../../types'
import { MOCK_TENANT_ID } from './tenants'
import { ROUTE_PAYMENTS_ID, ROUTE_USERS_ID, ROUTE_ORDERS_ID } from './routes'

const ago = (mins: number) => new Date(Date.now() - mins * 60000).toISOString()

export const seedAuditEvents: AuditEntry[] = [
  { eventId: 'ae-001', tenantId: MOCK_TENANT_ID, eventType: 'ROUTE_ACTIVATED',   aggregateType: 'ROUTE',  aggregateId: ROUTE_PAYMENTS_ID, actorId: 'admin',    correlationId: 'corr-001', occurredAt: ago(5),   recordedAt: ago(5)   },
  { eventId: 'ae-002', tenantId: MOCK_TENANT_ID, eventType: 'ROUTE_CREATED',     aggregateType: 'ROUTE',  aggregateId: ROUTE_PAYMENTS_ID, actorId: 'admin',    correlationId: 'corr-002', occurredAt: ago(30),  recordedAt: ago(30)  },
  { eventId: 'ae-003', tenantId: MOCK_TENANT_ID, eventType: 'FILTER_ATTACHED',   aggregateType: 'FILTER', aggregateId: 'cccccccc-0000-0000-0000-000000000001', actorId: 'admin', occurredAt: ago(25),  recordedAt: ago(25)  },
  { eventId: 'ae-004', tenantId: MOCK_TENANT_ID, eventType: 'ROUTE_UPDATED',     aggregateType: 'ROUTE',  aggregateId: ROUTE_USERS_ID,    actorId: 'admin',    correlationId: 'corr-004', occurredAt: ago(60),  recordedAt: ago(60)  },
  { eventId: 'ae-005', tenantId: MOCK_TENANT_ID, eventType: 'USER_CREATED',      aggregateType: 'USER',   aggregateId: 'bbbbbbbb-0000-0000-0000-000000000003', actorId: 'admin', occurredAt: ago(90),  recordedAt: ago(90)  },
  { eventId: 'ae-006', tenantId: MOCK_TENANT_ID, eventType: 'ROUTE_DEACTIVATED', aggregateType: 'ROUTE',  aggregateId: ROUTE_ORDERS_ID,   actorId: 'operator', correlationId: 'corr-006', occurredAt: ago(120), recordedAt: ago(120) },
  { eventId: 'ae-007', tenantId: MOCK_TENANT_ID, eventType: 'ROUTE_ACTIVATED',   aggregateType: 'ROUTE',  aggregateId: ROUTE_ORDERS_ID,   actorId: 'operator', correlationId: 'corr-007', occurredAt: ago(100), recordedAt: ago(100) },
  { eventId: 'ae-008', tenantId: MOCK_TENANT_ID, eventType: 'FILTER_CREATED',    aggregateType: 'FILTER', aggregateId: 'cccccccc-0000-0000-0000-000000000007', actorId: 'admin', occurredAt: ago(200), recordedAt: ago(200) },
  { eventId: 'ae-009', tenantId: MOCK_TENANT_ID, eventType: 'CERTIFICATE_UPLOADED', aggregateType: 'CERTIFICATE', aggregateId: 'ffffffff-0000-0000-0000-000000000001', actorId: 'admin', occurredAt: ago(300), recordedAt: ago(300) },
  { eventId: 'ae-010', tenantId: MOCK_TENANT_ID, eventType: 'GATEWAY_CONFIG_CHANGED', aggregateType: 'GATEWAY', aggregateId: 'gateway-1', actorId: 'admin', occurredAt: ago(400), recordedAt: ago(400) },
  { eventId: 'ae-011', tenantId: MOCK_TENANT_ID, eventType: 'ROUTE_DELETED',     aggregateType: 'ROUTE',  aggregateId: 'dddddddd-0000-0000-0000-000000000099', actorId: 'admin',    correlationId: 'corr-011', occurredAt: ago(500), recordedAt: ago(500) },
  { eventId: 'ae-012', tenantId: MOCK_TENANT_ID, eventType: 'USER_UPDATED',      aggregateType: 'USER',   aggregateId: 'bbbbbbbb-0000-0000-0000-000000000002', actorId: 'admin',    occurredAt: ago(600), recordedAt: ago(600) },
  { eventId: 'ae-013', tenantId: MOCK_TENANT_ID, eventType: 'TENANT_CREATED',    aggregateType: 'TENANT', aggregateId: MOCK_TENANT_ID, actorId: 'system', occurredAt: ago(1000), recordedAt: ago(1000) },
  { eventId: 'ae-014', tenantId: MOCK_TENANT_ID, eventType: 'FILTER_UPDATED',    aggregateType: 'FILTER', aggregateId: 'cccccccc-0000-0000-0000-000000000003', actorId: 'admin', occurredAt: ago(700), recordedAt: ago(700) },
  { eventId: 'ae-015', tenantId: MOCK_TENANT_ID, eventType: 'ROUTE_CREATED',     aggregateType: 'ROUTE',  aggregateId: ROUTE_USERS_ID,    actorId: 'admin',    occurredAt: ago(800), recordedAt: ago(800) },
  { eventId: 'ae-016', tenantId: MOCK_TENANT_ID, eventType: 'CERTIFICATE_REVOKED', aggregateType: 'CERTIFICATE', aggregateId: 'ffffffff-0000-0000-0000-000000000004', actorId: 'admin', occurredAt: ago(900), recordedAt: ago(900) },
  { eventId: 'ae-017', tenantId: MOCK_TENANT_ID, eventType: 'ROUTE_ACTIVATED',   aggregateType: 'ROUTE',  aggregateId: ROUTE_USERS_ID,    actorId: 'admin',    occurredAt: ago(750), recordedAt: ago(750) },
  { eventId: 'ae-018', tenantId: MOCK_TENANT_ID, eventType: 'FILTER_DETACHED',   aggregateType: 'FILTER', aggregateId: 'cccccccc-0000-0000-0000-000000000002', actorId: 'admin', occurredAt: ago(250), recordedAt: ago(250) },
  { eventId: 'ae-019', tenantId: MOCK_TENANT_ID, eventType: 'USER_DELETED',      aggregateType: 'USER',   aggregateId: 'bbbbbbbb-0000-0000-0000-000000000099', actorId: 'admin', occurredAt: ago(1200), recordedAt: ago(1200) },
  { eventId: 'ae-020', tenantId: MOCK_TENANT_ID, eventType: 'GATEWAY_RELOAD_REQUESTED', aggregateType: 'GATEWAY', aggregateId: 'gateway-1', actorId: 'admin', occurredAt: ago(10), recordedAt: ago(10) },
]

const paths = ['/api/v1/payments/charge', '/api/v1/users/profile', '/api/v1/orders/123', '/api/v1/products/list', '/api/v1/inventory/stock']
const statuses = [200, 200, 200, 201, 204, 400, 401, 404, 500]
const methods = ['GET', 'POST', 'PUT', 'DELETE']

export const seedRequestLogs: RequestLogDto[] = Array.from({ length: 20 }, (_, i) => ({
  id:           `rl-${String(i + 1).padStart(3, '0')}`,
  tenantId:     MOCK_TENANT_ID,
  routeId:      [ROUTE_PAYMENTS_ID, ROUTE_USERS_ID, ROUTE_ORDERS_ID][i % 3],
  routeName:    ['Payments API', 'Users Service', 'Orders API'][i % 3],
  correlationId: `req-corr-${i + 1}`,
  method:       methods[i % 4],
  path:         paths[i % 5],
  upstreamUri:  'http://upstream-service:8090',
  responseStatus: statuses[i % statuses.length],
  durationMs:   50 + (i * 17) % 450,
  clientIp:     `192.168.1.${(i % 20) + 10}`,
  requestedAt:  ago(i * 3),
  failed:       statuses[i % statuses.length] >= 500,
  replayStatus: statuses[i % statuses.length] >= 500 ? 'PENDING' : undefined,
  replayCount:  0,
}))

export const seedFailedRequests: FailedRequestDto[] = seedRequestLogs
  .filter(r => r.failed)
  .map(r => ({
    ...r,
    errorMessage: 'Internal Server Error: upstream connection refused',
  }))

export const seedReplayStats: ReplayStats = {
  pending:    seedFailedRequests.length,
  inProgress: 0,
  succeeded:  12,
  failed:     3,
  skipped:    1,
}

