/**
 * filterRegistry.ts — Single source of truth for ALL FilterType metadata.
 *
 * Every service that needs labels, categories, descriptions, or colours for
 * filter types imports from here. This eliminates the previous duplication
 * between FilterDefinitionForm.tsx (FILTER_TYPES array), FilterDefinitionList.tsx
 * (FILTER_TYPE_COLORS), and nodeMetadata.tsx (FILTER_META).
 */
import type { FilterType } from '../../types'

export interface FilterRegistryEntry {
  value: FilterType
  label: string
  category: FilterCategory
  description: string
  /** Tailwind text + bg + border classes — used for badges and canvas nodes */
  color: string // e.g. 'text-emerald-400'
  bg: string // e.g. 'bg-emerald-400/10'
  border: string // e.g. 'border-emerald-400/20'
}

export type FilterCategory =
  | 'Authentication'
  | 'Downstream Auth'
  | 'Rate Limiting'
  | 'Modification'
  | 'Transformation'
  | 'Validation'
  | 'Performance'
  | 'Resilience'
  | 'Observability'
  | 'Security'
  | 'Versioning'
  | 'Routing'
  | 'Custom'
  | 'AI'

export const CATEGORY_ORDER: FilterCategory[] = [
  'Authentication',
  'Downstream Auth',
  'Rate Limiting',
  'Modification',
  'Transformation',
  'Validation',
  'Performance',
  'Resilience',
  'Observability',
  'Security',
  'Versioning',
  'Routing',
  'Custom',
  'AI',
]

/** Full badge colour string for a category label chip */
export const CATEGORY_COLORS: Record<FilterCategory, string> = {
  Authentication: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
  'Downstream Auth': 'text-violet-400 bg-violet-400/10 border-violet-400/20',
  'Rate Limiting': 'text-amber-400 bg-amber-400/10 border-amber-400/20',
  Modification: 'text-blue-400 bg-blue-400/10 border-blue-400/20',
  Transformation: 'text-purple-400 bg-purple-400/10 border-purple-400/20',
  Validation: 'text-cyan-400 bg-cyan-400/10 border-cyan-400/20',
  Performance: 'text-lime-400 bg-lime-400/10 border-lime-400/20',
  Resilience: 'text-orange-400 bg-orange-400/10 border-orange-400/20',
  Observability: 'text-indigo-400 bg-indigo-400/10 border-indigo-400/20',
  Security: 'text-red-400 bg-red-400/10 border-red-400/20',
  Versioning: 'text-teal-400 bg-teal-400/10 border-teal-400/20',
  Routing: 'text-pink-400 bg-pink-400/10 border-pink-400/20',
  Custom: 'text-gray-400 bg-gray-400/10 border-gray-400/20',
  AI: 'text-fuchsia-400 bg-fuchsia-400/10 border-fuchsia-400/20',
}

export const FILTER_REGISTRY: FilterRegistryEntry[] = [
  // ── Authentication ────────────────────────────────────────────────────────────
  {
    value: 'AUTH_JWT',
    label: 'JWT Auth',
    category: 'Authentication',
    description:
      'Validate RS256/HS256 JWT bearer tokens; injects X-Auth-User-Id, X-Auth-Tenant-Id, X-Auth-Role, X-Auth-Email downstream',
    color: 'text-emerald-400',
    bg: 'bg-emerald-400/10',
    border: 'border-emerald-400/20',
  },
  {
    value: 'AUTH_API_KEY',
    label: 'API Key Auth',
    category: 'Authentication',
    description: 'Validate API keys from a configurable header or query parameter',
    color: 'text-blue-400',
    bg: 'bg-blue-400/10',
    border: 'border-blue-400/20',
  },
  {
    value: 'AUTH_BASIC',
    label: 'Basic Auth',
    category: 'Authentication',
    description: 'Inbound HTTP Basic Authentication against configured credentials',
    color: 'text-cyan-400',
    bg: 'bg-cyan-400/10',
    border: 'border-cyan-400/20',
  },
  {
    value: 'AUTH_OAUTH2',
    label: 'OAuth2',
    category: 'Authentication',
    description: 'Verify bearer tokens via an OAuth2 token introspection endpoint',
    color: 'text-teal-400',
    bg: 'bg-teal-400/10',
    border: 'border-teal-400/20',
  },
  {
    value: 'AUTH_MTLS',
    label: 'mTLS Auth',
    category: 'Authentication',
    description: 'Mutual TLS — validate client certificate against the certificate registry',
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/20',
  },
  {
    value: 'AUTH_CLIENT_ID',
    label: 'Client ID Auth',
    category: 'Authentication',
    description: 'Validate client identity via X-Client-Id header and certificate mapping',
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    border: 'border-violet-400/20',
  },
  {
    value: 'AUTH_CERT_VAULT',
    label: 'Cert Vault Auth',
    category: 'Authentication',
    description:
      'Authenticate caller by verifying their PEM certificate against a Certificate Group in the Cert Vault registry',
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    border: 'border-sky-400/20',
  },

  // ── Downstream Auth ───────────────────────────────────────────────────────────
  {
    value: 'DOWNSTREAM_BASIC_AUTH',
    label: 'Downstream Basic Auth',
    category: 'Downstream Auth',
    description: 'Inject Basic Auth credentials into outbound downstream requests',
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    border: 'border-violet-400/20',
  },
  {
    value: 'DOWNSTREAM_BEARER_CC',
    label: 'Downstream Bearer (CC)',
    category: 'Downstream Auth',
    description: 'Acquire an OAuth2 client-credentials token and inject it as Bearer downstream',
    color: 'text-purple-400',
    bg: 'bg-purple-400/10',
    border: 'border-purple-400/20',
  },

  // ── Rate Limiting ─────────────────────────────────────────────────────────────
  {
    value: 'RATE_LIMIT_FIXED_WINDOW',
    label: 'Fixed Window Rate Limit',
    category: 'Rate Limiting',
    description: 'Redis fixed-window counter (INCR + PEXPIRE) — simplest approach, potential boundary burst',
    color: 'text-orange-400',
    bg: 'bg-orange-400/10',
    border: 'border-orange-400/20',
  },
  {
    value: 'RATE_LIMIT_SLIDING_WINDOW',
    label: 'Sliding Window Rate Limit',
    category: 'Rate Limiting',
    description: 'Redis sliding-window rate limiter (sorted-set algorithm) — accurate, higher memory cost',
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    border: 'border-amber-400/20',
  },

  // ── Modification ─────────────────────────────────────────────────────────────
  {
    value: 'REQUEST_HEADER_MODIFY',
    label: 'Request Header Modify',
    category: 'Modification',
    description: 'Add, set or remove request headers before forwarding upstream',
    color: 'text-blue-300',
    bg: 'bg-blue-300/10',
    border: 'border-blue-300/20',
  },
  {
    value: 'RESPONSE_HEADER_MODIFY',
    label: 'Response Header Modify',
    category: 'Modification',
    description: 'Add, set or remove response headers after upstream replies',
    color: 'text-purple-300',
    bg: 'bg-purple-300/10',
    border: 'border-purple-300/20',
  },

  // ── Transformation ────────────────────────────────────────────────────────────
  {
    value: 'BODY_JOLT_TRANSFORM',
    label: 'Jolt Transform',
    category: 'Transformation',
    description: 'Transform JSON request and/or response body with a Jolt Chainr specification',
    color: 'text-purple-400',
    bg: 'bg-purple-400/10',
    border: 'border-purple-400/20',
  },

  // ── Validation ────────────────────────────────────────────────────────────────
  {
    value: 'VALIDATE_JSON_SCHEMA',
    label: 'JSON Schema Validate',
    category: 'Validation',
    description: 'Validate request body against a JSON Schema (Draft-07 by default)',
    color: 'text-cyan-300',
    bg: 'bg-cyan-300/10',
    border: 'border-cyan-300/20',
  },
  {
    value: 'REQUEST_SIZE_LIMIT',
    label: 'Request Size Limit',
    category: 'Validation',
    description: 'Enforce per-route maximum request body size; rejects with HTTP 413 when exceeded',
    color: 'text-cyan-300',
    bg: 'bg-cyan-300/10',
    border: 'border-cyan-300/20',
  },

  // ── Performance ────────────────────────────────────────────────────────────
  {
    value: 'RESPONSE_CACHE',
    label: 'Response Cache',
    category: 'Performance',
    description:
      'Per-route Redis-backed response caching with configurable TTL, Cache-Control respect, and cache key strategies. Injects X-Cache: HIT/MISS headers.',
    color: 'text-lime-400',
    bg: 'bg-lime-400/10',
    border: 'border-lime-400/20',
  },

  // ── Resilience ────────────────────────────────────────────────────────────────
  {
    value: 'TIMEOUT',
    label: 'Request Timeout',
    category: 'Resilience',
    description: 'Enforce a per-route maximum request duration; returns 504 if upstream does not respond in time',
    color: 'text-rose-400',
    bg: 'bg-rose-400/10',
    border: 'border-rose-400/20',
  },
  {
    value: 'CIRCUIT_BREAKER_V2',
    label: 'Circuit Breaker v2',
    category: 'Resilience',
    description:
      'Per-route Resilience4j circuit breaker with configurable failure/slow-call thresholds, half-open probing, WebSocket state broadcast, and manual override',
    color: 'text-orange-400',
    bg: 'bg-orange-400/10',
    border: 'border-orange-400/20',
  },
  {
    value: 'RETRY_V2',
    label: 'Retry v2',
    category: 'Resilience',
    description:
      'Per-route retry with exponential backoff, jitter, idempotency-aware logic (only retries unsafe methods with Idempotency-Key header), and configurable status/timeout retry conditions',
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    border: 'border-amber-400/20',
  },

  // ── Observability ─────────────────────────────────────────────────────────────
  {
    value: 'CORRELATION_ID',
    label: 'Correlation ID',
    category: 'Observability',
    description: 'Inject or propagate X-Correlation-Id (generate UUID if absent); runs at order −1000',
    color: 'text-indigo-300',
    bg: 'bg-indigo-300/10',
    border: 'border-indigo-300/20',
  },
  {
    value: 'REQUEST_LOGGER',
    label: 'Request Logger',
    category: 'Observability',
    description: 'Log requests/responses with sampling, header allow/denylists, path exclusions, and structured MDC logging',
    color: 'text-gray-300',
    bg: 'bg-gray-300/10',
    border: 'border-gray-300/20',
  },
  {
    value: 'TENANT_CONTEXT',
    label: 'Tenant Context',
    category: 'Observability',
    description: 'Resolve tenant context and control X-Tenant-Id propagation to upstream services',
    color: 'text-sky-300',
    bg: 'bg-sky-300/10',
    border: 'border-sky-300/20',
  },
  {
    value: 'CUSTOM_METRIC',
    label: 'Custom Metric',
    category: 'Observability',
    description: 'Increment a custom Micrometer counter with optional dynamic per-request tags',
    color: 'text-pink-400',
    bg: 'bg-pink-400/10',
    border: 'border-pink-400/20',
  },

  // ── Security ─────────────────────────────────────────────────────────────────
  {
    value: 'SECURITY_HEADERS',
    label: 'Security Headers',
    category: 'Security',
    description: 'Inject OWASP-recommended security response headers (HSTS, CSP, X-Frame-Options…)',
    color: 'text-red-400',
    bg: 'bg-red-400/10',
    border: 'border-red-400/20',
  },
  {
    value: 'CERT_ROTATION',
    label: 'Cert Rotation',
    category: 'Security',
    description: 'Enforce certificate rotation — reject requests with revoked or unknown client certificates',
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    border: 'border-sky-400/20',
  },
  {
    value: 'CERT_VAULT_EXPIRY_CHECK',
    label: 'Cert Vault Expiry Check',
    category: 'Security',
    description: 'Block or warn when all certificates in a Cert Group are expired, revoked, or approaching expiry',
    color: 'text-rose-300',
    bg: 'bg-rose-300/10',
    border: 'border-rose-300/20',
  },
  {
    value: 'IP_ACCESS_CONTROL',
    label: 'IP Access Control',
    category: 'Security',
    description:
      'Block or allow requests by client IP address or CIDR range. Supports IPv4/IPv6, X-Forwarded-For, and allowlist/denylist modes. Runs at order −1500 (before all auth filters).',
    color: 'text-red-300',
    bg: 'bg-red-300/10',
    border: 'border-red-300/20',
  },

  // ── Versioning ────────────────────────────────────────────────────────────────
  {
    value: 'API_VERSIONING',
    label: 'API Versioning',
    category: 'Versioning',
    description: 'Inject API version via header, query param, or path prefix rewrite',
    color: 'text-teal-400',
    bg: 'bg-teal-400/10',
    border: 'border-teal-400/20',
  },

  // ── Routing ───────────────────────────────────────────────────────────────────
  {
    value: 'CONDITIONAL_ROUTE',
    label: 'Conditional Route',
    category: 'Routing',
    description: 'Rewrite upstream URI when a header or query param matches a pattern',
    color: 'text-pink-400',
    bg: 'bg-pink-400/10',
    border: 'border-pink-400/20',
  },
  {
    value: 'USER_ID_PAYLOAD_ROUTING',
    label: 'User ID Payload Routing',
    category: 'Routing',
    description: 'Route to an alternative upstream when userId in the request body is in an allowlist',
    color: 'text-rose-400',
    bg: 'bg-rose-400/10',
    border: 'border-rose-400/20',
  },
  {
    value: 'GEO_ROUTE',
    label: 'Geographic Routing',
    category: 'Routing',
    description:
      'Route requests to geographically closest upstream using MaxMind GeoIP2 lookups. Injects X-Geo-Region header for downstream observability.',
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    border: 'border-sky-400/20',
  },

  // ── Custom ────────────────────────────────────────────────────────────────────
  {
    value: 'CUSTOM_SPEL',
    label: 'Custom (SpEL)',
    category: 'Custom',
    description: 'Sandboxed SpEL expression evaluation — returning false rejects with 403. Audited, complexity-limited.',
    color: 'text-gray-400',
    bg: 'bg-gray-400/10',
    border: 'border-gray-400/20',
  },

  // ── AI ────────────────────────────────────────────────────────────────────────
  {
    value: 'AI_FILTER',
    label: 'AI Filter',
    category: 'AI',
    description:
      'LLM-powered, policy-driven request filtering — ALLOW / BLOCK / FLAG verdicts via natural-language rules',
    color: 'text-fuchsia-400',
    bg: 'bg-fuchsia-400/10',
    border: 'border-fuchsia-400/20',
  },
  {
    value: 'AI_MODIFIER',
    label: 'AI Modifier',
    category: 'AI',
    description:
      'LLM-powered request mutation — PII scrubbing, payload translation, header rewriting before routing downstream',
    color: 'text-pink-500',
    bg: 'bg-pink-500/10',
    border: 'border-pink-500/20',
  },
]

/** Keyed map for O(1) lookups by FilterType value */
export const FILTER_REGISTRY_MAP = new Map<FilterType, FilterRegistryEntry>(FILTER_REGISTRY.map((e) => [e.value, e]))

export function getFilterEntry(type: FilterType | string): FilterRegistryEntry | undefined {
  return FILTER_REGISTRY_MAP.get(type as FilterType)
}

/** Returns filters grouped by category, in CATEGORY_ORDER order */
export function groupByCategory(
  entries: FilterRegistryEntry[],
): { category: FilterCategory; items: FilterRegistryEntry[] }[] {
  const map = new Map<FilterCategory, FilterRegistryEntry[]>()
  for (const entry of entries) {
    const arr = map.get(entry.category) ?? []
    arr.push(entry)
    map.set(entry.category, arr)
  }
  return CATEGORY_ORDER.filter((cat) => map.has(cat)).map((cat) => ({ category: cat, items: map.get(cat)! }))
}
