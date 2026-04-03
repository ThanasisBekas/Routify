/**
 * nodeMetadata.tsx — Shared visual metadata for all node/filter types in the
 * WorkflowBuilder. Centralised here so both the canvas nodes and the NodePalette
 * sidebar consume identical icons, colours, and category labels.
 */
import React from 'react'
import {
  Globe, Server, Shield, Gauge, RefreshCw, Code2, GitBranch,
  ToggleLeft, Zap, CheckCircle, Clock, Pause, Archive,
  Monitor, Radio,
  Lock, Key, Fingerprint, UserCheck, AlertTriangle,
  RotateCcw, Timer, Layers, Sliders, Tag,
  Eye, Activity, Hash, Brackets,
} from 'lucide-react'
import { MarkerType } from '@xyflow/react'

// ─── Filter visual metadata ────────────────────────────────────────────────────

export interface FilterMeta {
  icon: React.ReactNode
  color: string   // Tailwind text colour class
  bg: string      // Tailwind background tint class
  border: string  // Tailwind border colour class
}

export const FILTER_META: Record<string, FilterMeta> = {
  AUTH_JWT:                  { icon: <Shield className="w-4 h-4" />, color: 'text-emerald-400', bg: 'bg-emerald-400/10', border: 'border-emerald-400/25' },
  AUTH_API_KEY:              { icon: <Key className="w-4 h-4" />,    color: 'text-blue-400',    bg: 'bg-blue-400/10',    border: 'border-blue-400/25' },
  AUTH_BASIC:                { icon: <Lock className="w-4 h-4" />,   color: 'text-cyan-400',    bg: 'bg-cyan-400/10',    border: 'border-cyan-400/25' },
  AUTH_OAUTH2:               { icon: <UserCheck className="w-4 h-4" />, color: 'text-teal-400', bg: 'bg-teal-400/10',    border: 'border-teal-400/25' },
  AUTH_MTLS:                 { icon: <Fingerprint className="w-4 h-4" />, color: 'text-indigo-400', bg: 'bg-indigo-400/10', border: 'border-indigo-400/25' },
  AUTH_CLIENT_ID:            { icon: <Hash className="w-4 h-4" />,   color: 'text-violet-400',  bg: 'bg-violet-400/10',  border: 'border-violet-400/25' },
  AUTH_NONE:                 { icon: <Shield className="w-4 h-4" />, color: 'text-gray-400',    bg: 'bg-gray-400/10',    border: 'border-gray-400/25' },
  RATE_LIMIT_TOKEN_BUCKET:   { icon: <Gauge className="w-4 h-4" />,  color: 'text-yellow-400',  bg: 'bg-yellow-400/10',  border: 'border-yellow-400/25' },
  RATE_LIMIT_FIXED_WINDOW:   { icon: <Gauge className="w-4 h-4" />,  color: 'text-orange-400',  bg: 'bg-orange-400/10',  border: 'border-orange-400/25' },
  RATE_LIMIT_SLIDING_WINDOW: { icon: <Gauge className="w-4 h-4" />,  color: 'text-amber-400',   bg: 'bg-amber-400/10',   border: 'border-amber-400/25' },
  CIRCUIT_BREAKER:           { icon: <AlertTriangle className="w-4 h-4" />, color: 'text-orange-400', bg: 'bg-orange-400/10', border: 'border-orange-400/25' },
  RETRY:                     { icon: <RotateCcw className="w-4 h-4" />, color: 'text-amber-400', bg: 'bg-amber-400/10',  border: 'border-amber-400/25' },
  TIMEOUT:                   { icon: <Timer className="w-4 h-4" />,   color: 'text-rose-400',   bg: 'bg-rose-400/10',   border: 'border-rose-400/25' },
  BODY_JOLT_TRANSFORM:       { icon: <Code2 className="w-4 h-4" />,  color: 'text-purple-400', bg: 'bg-purple-400/10', border: 'border-purple-400/25' },
  BODY_JSONATA_TRANSFORM:    { icon: <Code2 className="w-4 h-4" />,  color: 'text-violet-400', bg: 'bg-violet-400/10', border: 'border-violet-400/25' },
  SPEL_TRANSFORM:            { icon: <Brackets className="w-4 h-4" />, color: 'text-fuchsia-400', bg: 'bg-fuchsia-400/10', border: 'border-fuchsia-400/25' },
  CONDITIONAL_ROUTE:         { icon: <GitBranch className="w-4 h-4" />, color: 'text-indigo-400', bg: 'bg-indigo-400/10', border: 'border-indigo-400/25' },
  API_VERSIONING:            { icon: <ToggleLeft className="w-4 h-4" />, color: 'text-cyan-400', bg: 'bg-cyan-400/10',  border: 'border-cyan-400/25' },
  SECURITY_HEADERS:          { icon: <Layers className="w-4 h-4" />, color: 'text-red-400',    bg: 'bg-red-400/10',    border: 'border-red-400/25' },
  CERT_ROTATION:             { icon: <RefreshCw className="w-4 h-4" />, color: 'text-sky-400', bg: 'bg-sky-400/10',    border: 'border-sky-400/25' },
  REQUEST_HEADER_MODIFY:     { icon: <Sliders className="w-4 h-4" />, color: 'text-blue-300',  bg: 'bg-blue-300/10',   border: 'border-blue-300/25' },
  RESPONSE_HEADER_MODIFY:    { icon: <Sliders className="w-4 h-4" />, color: 'text-purple-300', bg: 'bg-purple-300/10', border: 'border-purple-300/25' },
  VALIDATE_JSON_SCHEMA:      { icon: <CheckCircle className="w-4 h-4" />, color: 'text-cyan-300', bg: 'bg-cyan-300/10', border: 'border-cyan-300/25' },
  VALIDATE_REGEX:            { icon: <Tag className="w-4 h-4" />,    color: 'text-teal-300',   bg: 'bg-teal-300/10',   border: 'border-teal-300/25' },
  CORRELATION_ID:            { icon: <Hash className="w-4 h-4" />,   color: 'text-indigo-300', bg: 'bg-indigo-300/10', border: 'border-indigo-300/25' },
  REQUEST_LOGGER:            { icon: <Eye className="w-4 h-4" />,    color: 'text-gray-300',   bg: 'bg-gray-300/10',   border: 'border-gray-300/25' },
  TENANT_CONTEXT:            { icon: <Layers className="w-4 h-4" />, color: 'text-sky-300',    bg: 'bg-sky-300/10',    border: 'border-sky-300/25' },
  CUSTOM_METRIC:             { icon: <Activity className="w-4 h-4" />, color: 'text-pink-400', bg: 'bg-pink-400/10',   border: 'border-pink-400/25' },
}

export const DEFAULT_FILTER_META: FilterMeta = {
  icon: <Zap className="w-4 h-4" />,
  color: 'text-gray-400',
  bg: 'bg-gray-400/10',
  border: 'border-gray-400/25',
}

export const getFilterMeta = (type: string): FilterMeta =>
  FILTER_META[type] ?? DEFAULT_FILTER_META

// ─── Route status visual config ────────────────────────────────────────────────

export const STATUS_CFG = {
  ACTIVE:   { color: 'text-emerald-400', dot: 'bg-emerald-400', icon: <CheckCircle className="w-3.5 h-3.5" /> },
  DRAFT:    { color: 'text-amber-400',   dot: 'bg-amber-400',   icon: <Clock className="w-3.5 h-3.5" /> },
  DISABLED: { color: 'text-gray-400',    dot: 'bg-gray-400',    icon: <Pause className="w-3.5 h-3.5" /> },
  ARCHIVED: { color: 'text-red-400',     dot: 'bg-red-400',     icon: <Archive className="w-3.5 h-3.5" /> },
} as const

// ─── Palette node type catalog ────────────────────────────────────────────────

export interface PaletteNodeDef {
  type: string         // Maps to React Flow node type key
  label: string
  description: string
  icon: React.ReactNode
  color: string        // Tailwind accent text colour
  bg: string           // Tailwind accent bg colour
  border: string       // Tailwind accent border colour
  category: 'Core' | 'Auth' | 'Rate Limiting' | 'Resilience' | 'Transformation' | 'Routing' | 'Security' | 'Validation' | 'Modification' | 'Observability'
  /** If true, only one instance of this node type may exist on the canvas */
  singleUse?: boolean
  /**
   * If set, this palette node represents a filter type.
   * Dropping it onto the canvas will open the filter attach flow
   * rather than creating a generic placeholder node.
   */
  filterType?: string
}

export const PALETTE_NODES: PaletteNodeDef[] = [
  // ── Core structural nodes (single-use) ──────────────────────────────────────
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

  // ── Auth filters ────────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'AUTH_JWT',
    label: 'JWT Auth',
    description: 'Validate RS256 / HS256 JWT Bearer tokens',
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
    description: 'Authenticate via X-API-Key header',
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

  // ── Rate limiting filters ───────────────────────────────────────────────────
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
    description: 'Token-bucket burst-tolerant rate limiter',
    icon: <Gauge className="w-4 h-4" />,
    color: 'text-yellow-400',
    bg: 'bg-yellow-400/10',
    border: 'border-yellow-400/20',
    category: 'Rate Limiting',
  },

  // ── Resilience filters ──────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'CIRCUIT_BREAKER',
    label: 'Circuit Breaker',
    description: 'Resilience4j circuit breaker for upstream protection',
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
    description: 'Automatic retry with backoff on upstream failures',
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
    description: 'Hard timeout cap on upstream response time',
    icon: <Timer className="w-4 h-4" />,
    color: 'text-rose-400',
    bg: 'bg-rose-400/10',
    border: 'border-rose-400/20',
    category: 'Resilience',
  },

  // ── Transformation filters ──────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'BODY_JOLT_TRANSFORM',
    label: 'Jolt Transform',
    description: 'JSON-to-JSON transformation via Jolt spec',
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
    description: 'JSON-to-JSON transformation via JSONata expression',
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
    description: 'Spring Expression Language body/header transform',
    icon: <Brackets className="w-4 h-4" />,
    color: 'text-fuchsia-400',
    bg: 'bg-fuchsia-400/10',
    border: 'border-fuchsia-400/20',
    category: 'Transformation',
  },

  // ── Modification filters ────────────────────────────────────────────────────
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

  // ── Routing filters ─────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'CONDITIONAL_ROUTE',
    label: 'Conditional Route',
    description: 'Branch traffic based on request predicates',
    icon: <GitBranch className="w-4 h-4" />,
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/20',
    category: 'Routing',
  },
  {
    type: 'filterNode',
    filterType: 'API_VERSIONING',
    label: 'API Versioning',
    description: 'Route based on API version header or path segment',
    icon: <ToggleLeft className="w-4 h-4" />,
    color: 'text-cyan-400',
    bg: 'bg-cyan-400/10',
    border: 'border-cyan-400/20',
    category: 'Routing',
  },

  // ── Security filters ────────────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'SECURITY_HEADERS',
    label: 'Security Headers',
    description: 'Inject HSTS, CSP, X-Frame-Options, etc.',
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
    description: 'Trigger TLS certificate hot-swap on the gateway',
    icon: <RefreshCw className="w-4 h-4" />,
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    border: 'border-sky-400/20',
    category: 'Security',
  },

  // ── Validation filters ──────────────────────────────────────────────────────
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
    description: 'Validate path / header values against a regex',
    icon: <Tag className="w-4 h-4" />,
    color: 'text-teal-300',
    bg: 'bg-teal-300/10',
    border: 'border-teal-300/20',
    category: 'Validation',
  },

  // ── Observability filters ───────────────────────────────────────────────────
  {
    type: 'filterNode',
    filterType: 'CORRELATION_ID',
    label: 'Correlation ID',
    description: 'Inject / propagate X-Correlation-ID header',
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
    description: 'Log structured request / response metadata',
    icon: <Eye className="w-4 h-4" />,
    color: 'text-gray-300',
    bg: 'bg-gray-300/10',
    border: 'border-gray-300/20',
    category: 'Observability',
  },
  {
    type: 'filterNode',
    filterType: 'CUSTOM_METRIC',
    label: 'Custom Metric',
    description: 'Emit a custom Micrometer counter / timer',
    icon: <Activity className="w-4 h-4" />,
    color: 'text-pink-400',
    bg: 'bg-pink-400/10',
    border: 'border-pink-400/20',
    category: 'Observability',
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
  if (type.startsWith('AUTH_'))          return 'Authentication'
  if (type.startsWith('RATE_LIMIT_'))    return 'Rate Limiting'
  if (type.startsWith('REQUEST_HEADER_') || type.startsWith('RESPONSE_HEADER_') || type.startsWith('PATH_') || type.startsWith('QUERY_')) return 'Modification'
  if (type.startsWith('BODY_'))          return 'Transformation'
  if (type.startsWith('VALIDATE_'))      return 'Validation'
  if (['CIRCUIT_BREAKER', 'RETRY', 'TIMEOUT'].includes(type)) return 'Resilience'
  if (['SECURITY_HEADERS', 'CERT_ROTATION'].includes(type))   return 'Security'
  if (type === 'API_VERSIONING')         return 'Versioning'
  if (type === 'CONDITIONAL_ROUTE')      return 'Routing'
  return 'Observability'
}

// ─── Category order for palette ───────────────────────────────────────────────

export const CATEGORY_ORDER = [
  'Core', 'Auth', 'Rate Limiting', 'Resilience',
  'Transformation', 'Modification', 'Routing',
  'Security', 'Validation', 'Observability',
] as const

