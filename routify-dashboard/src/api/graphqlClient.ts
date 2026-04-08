/**
 * Lightweight GraphQL client for the Routify Analytics API.
 *
 * Uses the shared `apiClient` (Axios) from `src/api/client.ts` to ensure
 * JWT injection and tenant header are applied automatically.
 *
 * @module graphqlClient
 */
import { apiClient } from './client'

const GRAPHQL_ENDPOINT = '/api/v1/admin/graphql'

export interface GraphQLResponse<T> {
  data: T | null
  errors?: Array<{
    message: string
    locations?: Array<{ line: number; column: number }>
    path?: string[]
    extensions?: Record<string, unknown>
  }>
}

/**
 * Execute a GraphQL query against the admin-api analytics endpoint.
 *
 * @param query - GraphQL query string
 * @param variables - Optional query variables
 * @returns The typed response data
 * @throws Error if the response contains GraphQL errors
 */
export async function graphqlQuery<T>(query: string, variables?: Record<string, unknown>): Promise<T> {
  const response = await apiClient.post<GraphQLResponse<T>>(GRAPHQL_ENDPOINT, {
    query,
    variables,
  })

  if (response.data.errors && response.data.errors.length > 0) {
    const messages = response.data.errors.map((e) => e.message).join('; ')
    throw new Error(`GraphQL error: ${messages}`)
  }

  if (!response.data.data) {
    throw new Error('GraphQL response contained no data')
  }

  return response.data.data
}

// ─── Pre-built Analytics Queries ──────────────────────────────────────────

export const ROUTE_ANALYTICS_QUERY = `
  query RouteAnalytics($tenantId: ID!, $from: DateTime!, $to: DateTime!, $routeId: ID, $granularity: Granularity) {
    routeAnalytics(tenantId: $tenantId, from: $from, to: $to, routeId: $routeId, granularity: $granularity) {
      routes {
        routeId
        routeName
        timeSeries {
          timestamp
          requestCount
          errorCount
          errorRate
          p50LatencyMs
          p95LatencyMs
          p99LatencyMs
          avgLatencyMs
          statusCodes {
            code
            count
          }
        }
        aggregate {
          totalRequests
          totalErrors
          errorRate
          avgLatencyMs
          p50LatencyMs
          p95LatencyMs
          p99LatencyMs
        }
      }
      totals {
        totalRequests
        totalErrors
        errorRate
        avgLatencyMs
        p50LatencyMs
        p95LatencyMs
        p99LatencyMs
      }
    }
  }
`

export const TENANT_USAGE_QUERY = `
  query TenantUsage($tenantId: ID!, $from: DateTime!, $to: DateTime!) {
    tenantUsage(tenantId: $tenantId, from: $from, to: $to) {
      tenantId
      plan
      routes { used limit percentage }
      filters { used limit percentage }
      requests { used limit percentage }
      dailyUsage {
        date
        requestCount
        errorCount
        routeCount
      }
    }
  }
`

export const CERT_EXPIRY_QUERY = `
  query CertExpiryReport($tenantId: ID!) {
    certExpiryReport(tenantId: $tenantId) {
      totalCerts
      expiringSoon
      expired
      certs {
        certId
        alias
        daysUntilExpiry
        notAfter
        status
        autoRenew
      }
    }
  }
`

export const AUDIT_TIMELINE_QUERY = `
  query AuditTimeline($tenantId: ID!, $from: DateTime!, $to: DateTime!, $aggregateType: String, $limit: Int) {
    auditTimeline(tenantId: $tenantId, from: $from, to: $to, aggregateType: $aggregateType, limit: $limit) {
      eventId
      eventType
      aggregateType
      aggregateId
      actorId
      occurredAt
    }
  }
`

export const AI_FILTER_ANALYTICS_QUERY = `
  query AiFilterAnalytics($tenantId: ID!, $from: DateTime!, $to: DateTime!, $filterId: ID, $granularity: Granularity) {
    aiFilterAnalytics(tenantId: $tenantId, from: $from, to: $to, filterId: $filterId, granularity: $granularity) {
      filters {
        filterId
        filterName
        totalDecisions
        allowCount
        blockCount
        flagCount
        avgLatencyMs
        cacheHitRate
        accuracyScore
        timeSeries {
          timestamp
          decisions
          blockRate
          avgLatencyMs
        }
      }
    }
  }
`
