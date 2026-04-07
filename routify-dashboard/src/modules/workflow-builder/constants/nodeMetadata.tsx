/**
 * nodeMetadata.tsx — Shared visual metadata for all node/filter types in the
 * WorkflowBuilder. Centralised here so both the canvas nodes and the NodePalette
 * sidebar consume identical icons, colours, and category labels.
 *
 * Filter visual metadata (color/bg/border) is derived from filterRegistry.ts
 * to keep the two in sync. Icons are added here because they are React nodes
 * (JSX) that the common registry cannot hold (no React dependency in filterRegistry).
 *
 * Note: react-refresh/only-export-components is disabled for this file because it
 * intentionally exports JSX-containing constants (icon React nodes used as data),
 * not rendered components. Fast-refresh still works — non-component exports are
 * re-evaluated on HMR without issue.
 */
import React from 'react'
import {
  Globe,
  Server,
  Shield,
  Gauge,
  RefreshCw,
  Code2,
  GitBranch,
  ToggleLeft,
  Zap,
  CheckCircle,
  Clock,
  Pause,
  Archive,
  Monitor,
  Radio,
  Lock,
  Key,
  Fingerprint,
  UserCheck,
  AlertTriangle,
  RotateCcw,
  Timer,
  Layers,
  Sliders,
  Tag,
  Eye,
  Activity,
  Hash,
  Brackets,
  ShieldCheck,
  ArrowRightLeft,
  ChevronsRight,
  FlaskConical,
  Brain,
  Wand2,
} from 'lucide-react'
import { MarkerType } from '@xyflow/react'
import { FILTER_REGISTRY_MAP } from '../../filters/filterRegistry'

// ─── Filter visual metadata ────────────────────────────────────────────────────

export interface FilterMeta {
  icon: React.ReactNode
  color: string // Tailwind text colour class
  bg: string // Tailwind background tint class
  border: string // Tailwind border colour class
}

/**
 * Icon-only overrides — the registry provides color/bg/border,
 * we augment with JSX icons here.
 */
const FILTER_ICONS: Record<string, React.ReactNode> = {
  AUTH_JWT: <Shield className="w-4 h-4" />,
  AUTH_API_KEY: <Key className="w-4 h-4" />,
  AUTH_BASIC: <Lock className="w-4 h-4" />,
  AUTH_OAUTH2: <UserCheck className="w-4 h-4" />,
  AUTH_MTLS: <Fingerprint className="w-4 h-4" />,
  AUTH_CLIENT_ID: <Hash className="w-4 h-4" />,
  AUTH_CERT_VAULT: <ShieldCheck className="w-4 h-4" />,
  DOWNSTREAM_BASIC_AUTH: <ArrowRightLeft className="w-4 h-4" />,
  DOWNSTREAM_BEARER_CC: <ChevronsRight className="w-4 h-4" />,
  RATE_LIMIT_FIXED_WINDOW: <Gauge className="w-4 h-4" />,
  RATE_LIMIT_SLIDING_WINDOW: <Gauge className="w-4 h-4" />,
  RATE_LIMIT_TOKEN_BUCKET: <Gauge className="w-4 h-4" />,
  CIRCUIT_BREAKER: <AlertTriangle className="w-4 h-4" />,
  RETRY: <RotateCcw className="w-4 h-4" />,
  TIMEOUT: <Timer className="w-4 h-4" />,
  BODY_JOLT_TRANSFORM: <Code2 className="w-4 h-4" />,
  BODY_JSONATA_TRANSFORM: <Code2 className="w-4 h-4" />,
  SPEL_TRANSFORM: <Brackets className="w-4 h-4" />,
  REQUEST_HEADER_MODIFY: <Sliders className="w-4 h-4" />,
  RESPONSE_HEADER_MODIFY: <Sliders className="w-4 h-4" />,
  VALIDATE_JSON_SCHEMA: <CheckCircle className="w-4 h-4" />,
  VALIDATE_REGEX: <Tag className="w-4 h-4" />,
  CONDITIONAL_ROUTE: <GitBranch className="w-4 h-4" />,
  USER_ID_PAYLOAD_ROUTING: <FlaskConical className="w-4 h-4" />,
  API_VERSIONING: <ToggleLeft className="w-4 h-4" />,
  SECURITY_HEADERS: <Layers className="w-4 h-4" />,
  CERT_ROTATION: <RefreshCw className="w-4 h-4" />,
  CERT_VAULT_EXPIRY_CHECK: <ShieldCheck className="w-4 h-4" />,
  CORRELATION_ID: <Hash className="w-4 h-4" />,
  REQUEST_LOGGER: <Eye className="w-4 h-4" />,
  TENANT_CONTEXT: <Layers className="w-4 h-4" />,
  CUSTOM_METRIC: <Activity className="w-4 h-4" />,
  CUSTOM_SPEL: <Brackets className="w-4 h-4" />,
  AI_FILTER: <Brain className="w-4 h-4" />,
  AI_MODIFIER: <Wand2 className="w-4 h-4" />,
  AUTH_NONE: <Shield className="w-4 h-4" />,
}

/** Build FILTER_META by merging registry color/bg/border with local icons */
export const FILTER_META: Record<string, FilterMeta> = (() => {
  const meta: Record<string, FilterMeta> = {}
  // Seed from filterRegistry (covers all active types)
  FILTER_REGISTRY_MAP.forEach((entry, key) => {
    meta[key] = {
      icon: FILTER_ICONS[key] ?? <Zap className="w-4 h-4" />,
      color: entry.color,
      bg: entry.bg,
      border: entry.border.replace('/20', '/25'),
    }
  })
  // Legacy / deprecated types not in registry — keep backward compat
  const legacy: Record<string, FilterMeta> = {
    AUTH_NONE: {
      icon: <Shield className="w-4 h-4" />,
      color: 'text-gray-400',
      bg: 'bg-gray-400/10',
      border: 'border-gray-400/25',
    },
    RATE_LIMIT_TOKEN_BUCKET: {
      icon: <Gauge className="w-4 h-4" />,
      color: 'text-yellow-400',
      bg: 'bg-yellow-400/10',
      border: 'border-yellow-400/25',
    },
    CIRCUIT_BREAKER: {
      icon: <AlertTriangle className="w-4 h-4" />,
      color: 'text-orange-400',
      bg: 'bg-orange-400/10',
      border: 'border-orange-400/25',
    },
    RETRY: {
      icon: <RotateCcw className="w-4 h-4" />,
      color: 'text-amber-400',
      bg: 'bg-amber-400/10',
      border: 'border-amber-400/25',
    },
    BODY_JSONATA_TRANSFORM: {
      icon: <Code2 className="w-4 h-4" />,
      color: 'text-violet-400',
      bg: 'bg-violet-400/10',
      border: 'border-violet-400/25',
    },
    SPEL_TRANSFORM: {
      icon: <Brackets className="w-4 h-4" />,
      color: 'text-fuchsia-400',
      bg: 'bg-fuchsia-400/10',
      border: 'border-fuchsia-400/25',
    },
    VALIDATE_REGEX: {
      icon: <Tag className="w-4 h-4" />,
      color: 'text-teal-300',
      bg: 'bg-teal-300/10',
      border: 'border-teal-300/25',
    },
  }
  Object.assign(meta, legacy)
  return meta
})() as Record<string, FilterMeta>

export const DEFAULT_FILTER_META: FilterMeta = {
  icon: <Zap className="w-4 h-4" />,
  color: 'text-gray-400',
  bg: 'bg-gray-400/10',
  border: 'border-gray-400/25',
}

export const getFilterMeta = (type: string): FilterMeta => FILTER_META[type] ?? DEFAULT_FILTER_META

// ─── Route status visual config ────────────────────────────────────────────────

export const STATUS_CFG = {
  ACTIVE: { color: 'text-emerald-400', dot: 'bg-emerald-400', icon: <CheckCircle className="w-3.5 h-3.5" /> },
  DRAFT: { color: 'text-amber-400', dot: 'bg-amber-400', icon: <Clock className="w-3.5 h-3.5" /> },
  DISABLED: { color: 'text-gray-400', dot: 'bg-gray-400', icon: <Pause className="w-3.5 h-3.5" /> },
  ARCHIVED: { color: 'text-red-400', dot: 'bg-red-400', icon: <Archive className="w-3.5 h-3.5" /> },
} as const

// ─── Palette node type catalog ────────────────────────────────────────────────

export interface PaletteNodeDef {
  type: string
  label: string
  description: string
  icon: React.ReactNode
  color: string
  bg: string
  border: string
  category:
    | 'Core'
    | 'Auth'
    | 'Downstream Auth'
    | 'Rate Limiting'
    | 'Resilience'
    | 'Transformation'
    | 'Routing'
    | 'Security'
    | 'Validation'
    | 'Modification'
    | 'Observability'
    | 'Custom'
    | 'AI'
  singleUse?: boolean
  filterType?: string
}

export const PALETTE_NODES: PaletteNodeDef[] = [
  // ── Core structural nodes ────────────────────────────────────────────────────
  {
    type: 'clientNode',
    label: 'Client',
    description: 'Incoming HTTP request from the client',
    icon: <Monitor className="w-4 h-4" />,
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/20',
    category: 'Core',
    singleUse: true,
  },
  {
    type: 'routeNode',
    label: 'Route Trigger',
    description: 'HTTP path matcher & method filter',
    icon: <Radio className="w-4 h-4" />,
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/20',
    category: 'Core',
    singleUse: true,
  },
  {
    type: 'upstreamNode',
    label: 'Upstream',
    description: 'Proxy target — HTTP or load-balanced',
    icon: <Server className="w-4 h-4" />,
    color: 'text-emerald-400',
    bg: 'bg-emerald-400/10',
    border: 'border-emerald-400/20',
    category: 'Core',
    singleUse: true,
  },
  {
    type: 'responseNode',
    label: 'Response',
    description: 'Final HTTP response returned to the client',
    icon: <Globe className="w-4 h-4" />,
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    border: 'border-sky-400/20',
    category: 'Core',
    singleUse: true,
  },

  // ── Auth ─────────────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'AUTH_JWT',
    label: 'JWT Auth',
    description: 'Validate RS256/HS256 JWT bearer tokens',
    icon: <Shield className="w-4 h-4" />,
    color: 'text-emerald-400',
    bg: 'bg-emerald-400/10',
    border: 'border-emerald-400/20',
    category: 'Auth',
  },
  {
    type: 'filterNode',
    filterType: 'AUTH_API_KEY',
    label: 'API Key Auth',
    description: 'Authenticate via X-API-Key header or query param',
    icon: <Key className="w-4 h-4" />,
    color: 'text-blue-400',
    bg: 'bg-blue-400/10',
    border: 'border-blue-400/20',
    category: 'Auth',
  },
  {
    type: 'filterNode',
    filterType: 'AUTH_BASIC',
    label: 'Basic Auth',
    description: 'HTTP Basic authentication (username/password)',
    icon: <Lock className="w-4 h-4" />,
    color: 'text-cyan-400',
    bg: 'bg-cyan-400/10',
    border: 'border-cyan-400/20',
    category: 'Auth',
  },
  {
    type: 'filterNode',
    filterType: 'AUTH_OAUTH2',
    label: 'OAuth2 Introspect',
    description: 'Validate tokens via OAuth2 introspection endpoint',
    icon: <UserCheck className="w-4 h-4" />,
    color: 'text-teal-400',
    bg: 'bg-teal-400/10',
    border: 'border-teal-400/20',
    category: 'Auth',
  },
  {
    type: 'filterNode',
    filterType: 'AUTH_MTLS',
    label: 'mTLS Auth',
    description: 'Mutual TLS client certificate authentication',
    icon: <Fingerprint className="w-4 h-4" />,
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/20',
    category: 'Auth',
  },
  {
    type: 'filterNode',
    filterType: 'AUTH_CLIENT_ID',
    label: 'Client ID Auth',
    description: 'Authenticate via client identifier header',
    icon: <Hash className="w-4 h-4" />,
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    border: 'border-violet-400/20',
    category: 'Auth',
  },
  {
    type: 'filterNode',
    filterType: 'AUTH_CERT_VAULT',
    label: 'Cert Vault Auth',
    description: 'Authenticate via Cert Vault certificate registry',
    icon: <ShieldCheck className="w-4 h-4" />,
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    border: 'border-sky-400/20',
    category: 'Auth',
  },

  // ── Downstream Auth ───────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'DOWNSTREAM_BASIC_AUTH',
    label: 'Downstream Basic Auth',
    description: 'Inject Basic credentials into outbound upstream requests',
    icon: <ArrowRightLeft className="w-4 h-4" />,
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    border: 'border-violet-400/20',
    category: 'Downstream Auth',
  },
  {
    type: 'filterNode',
    filterType: 'DOWNSTREAM_BEARER_CC',
    label: 'Downstream Bearer (CC)',
    description: 'Inject OAuth2 client-credentials token as Bearer downstream',
    icon: <ChevronsRight className="w-4 h-4" />,
    color: 'text-purple-400',
    bg: 'bg-purple-400/10',
    border: 'border-purple-400/20',
    category: 'Downstream Auth',
  },

  // ── Rate Limiting ─────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'RATE_LIMIT_FIXED_WINDOW',
    label: 'Fixed Window',
    description: 'Fixed-window request rate limiter (Redis)',
    icon: <Gauge className="w-4 h-4" />,
    color: 'text-orange-400',
    bg: 'bg-orange-400/10',
    border: 'border-orange-400/20',
    category: 'Rate Limiting',
  },
  {
    type: 'filterNode',
    filterType: 'RATE_LIMIT_SLIDING_WINDOW',
    label: 'Sliding Window',
    description: 'Sliding-window request rate limiter (Redis)',
    icon: <Gauge className="w-4 h-4" />,
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    border: 'border-amber-400/20',
    category: 'Rate Limiting',
  },
  {
    type: 'filterNode',
    filterType: 'RATE_LIMIT_TOKEN_BUCKET',
    label: 'Token Bucket',
    description: 'Token-bucket burst-tolerant rate limiter (legacy)',
    icon: <Gauge className="w-4 h-4" />,
    color: 'text-yellow-400',
    bg: 'bg-yellow-400/10',
    border: 'border-yellow-400/20',
    category: 'Rate Limiting',
  },

  // ── Resilience ────────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'CIRCUIT_BREAKER',
    label: 'Circuit Breaker',
    description: 'Resilience4j circuit breaker for upstream protection (legacy)',
    icon: <AlertTriangle className="w-4 h-4" />,
    color: 'text-orange-400',
    bg: 'bg-orange-400/10',
    border: 'border-orange-400/20',
    category: 'Resilience',
  },
  {
    type: 'filterNode',
    filterType: 'RETRY',
    label: 'Retry',
    description: 'Automatic retry with backoff on upstream failures (legacy)',
    icon: <RotateCcw className="w-4 h-4" />,
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    border: 'border-amber-400/20',
    category: 'Resilience',
  },
  {
    type: 'filterNode',
    filterType: 'TIMEOUT',
    label: 'Timeout',
    description: 'Hard timeout cap on upstream response time (504 on exceed)',
    icon: <Timer className="w-4 h-4" />,
    color: 'text-rose-400',
    bg: 'bg-rose-400/10',
    border: 'border-rose-400/20',
    category: 'Resilience',
  },

  // ── Transformation ────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'BODY_JOLT_TRANSFORM',
    label: 'Jolt Transform',
    description: 'JSON-to-JSON request/response transformation via Jolt spec',
    icon: <Code2 className="w-4 h-4" />,
    color: 'text-purple-400',
    bg: 'bg-purple-400/10',
    border: 'border-purple-400/20',
    category: 'Transformation',
  },
  {
    type: 'filterNode',
    filterType: 'BODY_JSONATA_TRANSFORM',
    label: 'JSONata Transform',
    description: 'JSON-to-JSON transformation via JSONata expression (legacy)',
    icon: <Code2 className="w-4 h-4" />,
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    border: 'border-violet-400/20',
    category: 'Transformation',
  },
  {
    type: 'filterNode',
    filterType: 'SPEL_TRANSFORM',
    label: 'SpEL Transform',
    description: 'Spring Expression Language body/header transform (legacy)',
    icon: <Brackets className="w-4 h-4" />,
    color: 'text-fuchsia-400',
    bg: 'bg-fuchsia-400/10',
    border: 'border-fuchsia-400/20',
    category: 'Transformation',
  },

  // ── Modification ──────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'REQUEST_HEADER_MODIFY',
    label: 'Request Headers',
    description: 'Add / remove / rewrite request headers',
    icon: <Sliders className="w-4 h-4" />,
    color: 'text-blue-300',
    bg: 'bg-blue-300/10',
    border: 'border-blue-300/20',
    category: 'Modification',
  },
  {
    type: 'filterNode',
    filterType: 'RESPONSE_HEADER_MODIFY',
    label: 'Response Headers',
    description: 'Add / remove / rewrite response headers',
    icon: <Sliders className="w-4 h-4" />,
    color: 'text-purple-300',
    bg: 'bg-purple-300/10',
    border: 'border-purple-300/20',
    category: 'Modification',
  },

  // ── Routing ───────────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'CONDITIONAL_ROUTE',
    label: 'Conditional Route',
    description: 'Branch traffic based on header or query param predicates',
    icon: <GitBranch className="w-4 h-4" />,
    color: 'text-pink-400',
    bg: 'bg-pink-400/10',
    border: 'border-pink-400/20',
    category: 'Routing',
  },
  {
    type: 'filterNode',
    filterType: 'USER_ID_PAYLOAD_ROUTING',
    label: 'User ID Payload Routing',
    description: 'Route to an alternative upstream when userId is in allowlist',
    icon: <FlaskConical className="w-4 h-4" />,
    color: 'text-rose-400',
    bg: 'bg-rose-400/10',
    border: 'border-rose-400/20',
    category: 'Routing',
  },
  {
    type: 'filterNode',
    filterType: 'API_VERSIONING',
    label: 'API Versioning',
    description: 'Route based on API version header or path segment',
    icon: <ToggleLeft className="w-4 h-4" />,
    color: 'text-teal-400',
    bg: 'bg-teal-400/10',
    border: 'border-teal-400/20',
    category: 'Routing',
  },

  // ── Security ──────────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'SECURITY_HEADERS',
    label: 'Security Headers',
    description: 'Inject HSTS, CSP, X-Frame-Options, and other OWASP headers',
    icon: <Layers className="w-4 h-4" />,
    color: 'text-red-400',
    bg: 'bg-red-400/10',
    border: 'border-red-400/20',
    category: 'Security',
  },
  {
    type: 'filterNode',
    filterType: 'CERT_ROTATION',
    label: 'Cert Rotation',
    description: 'Enforce certificate rotation; rejects revoked certs',
    icon: <RefreshCw className="w-4 h-4" />,
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    border: 'border-sky-400/20',
    category: 'Security',
  },
  {
    type: 'filterNode',
    filterType: 'CERT_VAULT_EXPIRY_CHECK',
    label: 'Cert Vault Expiry Check',
    description: 'Block when all certs in a group are expired or approaching expiry',
    icon: <ShieldCheck className="w-4 h-4" />,
    color: 'text-rose-300',
    bg: 'bg-rose-300/10',
    border: 'border-rose-300/20',
    category: 'Security',
  },

  // ── Validation ────────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'VALIDATE_JSON_SCHEMA',
    label: 'JSON Schema Validate',
    description: 'Validate request body against a JSON Schema',
    icon: <CheckCircle className="w-4 h-4" />,
    color: 'text-cyan-300',
    bg: 'bg-cyan-300/10',
    border: 'border-cyan-300/20',
    category: 'Validation',
  },
  {
    type: 'filterNode',
    filterType: 'VALIDATE_REGEX',
    label: 'Regex Validate',
    description: 'Validate path / header values against a regex (legacy)',
    icon: <Tag className="w-4 h-4" />,
    color: 'text-teal-300',
    bg: 'bg-teal-300/10',
    border: 'border-teal-300/20',
    category: 'Validation',
  },

  // ── Observability ─────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'CORRELATION_ID',
    label: 'Correlation ID',
    description: 'Inject / propagate X-Correlation-ID header; runs at order −1000',
    icon: <Hash className="w-4 h-4" />,
    color: 'text-indigo-300',
    bg: 'bg-indigo-300/10',
    border: 'border-indigo-300/20',
    category: 'Observability',
  },
  {
    type: 'filterNode',
    filterType: 'REQUEST_LOGGER',
    label: 'Request Logger',
    description: 'Structured request/response logging with sampling, path exclusions, and header control',
    icon: <Eye className="w-4 h-4" />,
    color: 'text-gray-300',
    bg: 'bg-gray-300/10',
    border: 'border-gray-300/20',
    category: 'Observability',
  },
  {
    type: 'filterNode',
    filterType: 'TENANT_CONTEXT',
    label: 'Tenant Context',
    description: 'Resolve tenant context and control X-Tenant-Id propagation to upstream',
    icon: <Layers className="w-4 h-4" />,
    color: 'text-sky-300',
    bg: 'bg-sky-300/10',
    border: 'border-sky-300/20',
    category: 'Observability',
  },
  {
    type: 'filterNode',
    filterType: 'CUSTOM_METRIC',
    label: 'Custom Metric',
    description: 'Emit a custom Micrometer counter / timer with dynamic tags',
    icon: <Activity className="w-4 h-4" />,
    color: 'text-pink-400',
    bg: 'bg-pink-400/10',
    border: 'border-pink-400/20',
    category: 'Observability',
  },

  // ── Custom ────────────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'CUSTOM_SPEL',
    label: 'Custom (SpEL)',
    description: 'Evaluate a Spring Expression Language expression; false → 403',
    icon: <Brackets className="w-4 h-4" />,
    color: 'text-gray-400',
    bg: 'bg-gray-400/10',
    border: 'border-gray-400/20',
    category: 'Custom',
  },

  // ── AI ────────────────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'AI_FILTER',
    label: 'AI Filter',
    description: 'LLM-powered policy-driven filter — ALLOW / BLOCK / FLAG verdicts',
    icon: <Brain className="w-4 h-4" />,
    color: 'text-fuchsia-400',
    bg: 'bg-fuchsia-400/10',
    border: 'border-fuchsia-400/20',
    category: 'AI',
  },
  {
    type: 'filterNode',
    filterType: 'AI_MODIFIER',
    label: 'AI Modifier',
    description: 'LLM-powered request mutation — PII scrubbing, payload translation',
    icon: <Wand2 className="w-4 h-4" />,
    color: 'text-pink-500',
    bg: 'bg-pink-500/10',
    border: 'border-pink-500/20',
    category: 'AI',
  },
]

// ─── Edge style factory ────────────────────────────────────────────────────────

export function edgeStyle(type: 'pre' | 'post' | 'route' | 'default') {
  const c = { pre: '#6366f1', post: '#a855f7', route: '#10b981', default: '#374151' }[type]
  return {
    type: 'smoothstep' as const,
    animated: type !== 'default',
    style: { stroke: c, strokeWidth: 2 },
    markerEnd: { type: MarkerType.ArrowClosed, color: c, width: 14, height: 14 },
  }
}

// ─── Filter category helper ───────────────────────────────────────────────────

export function filterCategory(type: string): string {
  if (type.startsWith('AUTH_')) return 'Authentication'
  if (type.startsWith('DOWNSTREAM_')) return 'Downstream Auth'
  if (type.startsWith('RATE_LIMIT_')) return 'Rate Limiting'
  if (
    type.startsWith('REQUEST_HEADER_') ||
    type.startsWith('RESPONSE_HEADER_') ||
    type.startsWith('PATH_') ||
    type.startsWith('QUERY_')
  )
    return 'Modification'
  if (type.startsWith('BODY_')) return 'Transformation'
  if (type.startsWith('VALIDATE_')) return 'Validation'
  if (['CIRCUIT_BREAKER', 'RETRY', 'TIMEOUT'].includes(type)) return 'Resilience'
  if (['SECURITY_HEADERS', 'CERT_ROTATION', 'CERT_VAULT_EXPIRY_CHECK'].includes(type)) return 'Security'
  if (type === 'API_VERSIONING') return 'Routing'
  if (['CONDITIONAL_ROUTE', 'USER_ID_PAYLOAD_ROUTING'].includes(type)) return 'Routing'
  if (['CORRELATION_ID', 'REQUEST_LOGGER', 'TENANT_CONTEXT', 'CUSTOM_METRIC'].includes(type)) return 'Observability'
  if (type === 'CUSTOM_SPEL') return 'Custom'
  if (type.startsWith('AI_')) return 'AI'
  return 'Observability'
}

// ─── Category order for palette ───────────────────────────────────────────────

export const CATEGORY_ORDER = [
  'Core',
  'Auth',
  'Downstream Auth',
  'Rate Limiting',
  'Resilience',
  'Transformation',
  'Modification',
  'Routing',
  'Security',
  'Validation',
  'Observability',
  'Custom',
  'AI',
] as const
