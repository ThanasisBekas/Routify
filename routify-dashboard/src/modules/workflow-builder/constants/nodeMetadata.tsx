/**
 * nodeMetadata.tsx — Shared visual metadata for all node/filter types in the
 * WorkflowBuilder. Centralised here so both the canvas nodes and the NodePalette
 * sidebar consume identical icons, colours, and category labels.
 */
import React from 'react'
import {
  Globe, Server, Shield, Gauge, RefreshCw, Code2, GitBranch,
  ToggleLeft, Zap, CheckCircle, Clock, Pause, Archive,
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
  AUTH_API_KEY:              { icon: <Shield className="w-4 h-4" />, color: 'text-blue-400',    bg: 'bg-blue-400/10',    border: 'border-blue-400/25' },
  AUTH_BASIC:                { icon: <Shield className="w-4 h-4" />, color: 'text-cyan-400',    bg: 'bg-cyan-400/10',    border: 'border-cyan-400/25' },
  AUTH_OAUTH2:               { icon: <Shield className="w-4 h-4" />, color: 'text-teal-400',    bg: 'bg-teal-400/10',    border: 'border-teal-400/25' },
  AUTH_MTLS:                 { icon: <Shield className="w-4 h-4" />, color: 'text-indigo-400',  bg: 'bg-indigo-400/10',  border: 'border-indigo-400/25' },
  AUTH_CLIENT_ID:            { icon: <Shield className="w-4 h-4" />, color: 'text-violet-400',  bg: 'bg-violet-400/10',  border: 'border-violet-400/25' },
  AUTH_NONE:                 { icon: <Shield className="w-4 h-4" />, color: 'text-gray-400',    bg: 'bg-gray-400/10',    border: 'border-gray-400/25' },
  RATE_LIMIT_TOKEN_BUCKET:   { icon: <Gauge className="w-4 h-4" />,  color: 'text-yellow-400',  bg: 'bg-yellow-400/10',  border: 'border-yellow-400/25' },
  RATE_LIMIT_FIXED_WINDOW:   { icon: <Gauge className="w-4 h-4" />,  color: 'text-orange-400',  bg: 'bg-orange-400/10',  border: 'border-orange-400/25' },
  RATE_LIMIT_SLIDING_WINDOW: { icon: <Gauge className="w-4 h-4" />,  color: 'text-amber-400',   bg: 'bg-amber-400/10',   border: 'border-amber-400/25' },
  CIRCUIT_BREAKER:           { icon: <RefreshCw className="w-4 h-4" />, color: 'text-orange-400', bg: 'bg-orange-400/10', border: 'border-orange-400/25' },
  RETRY:                     { icon: <RefreshCw className="w-4 h-4" />, color: 'text-amber-400',  bg: 'bg-amber-400/10',  border: 'border-amber-400/25' },
  TIMEOUT:                   { icon: <Clock className="w-4 h-4" />,     color: 'text-rose-400',   bg: 'bg-rose-400/10',   border: 'border-rose-400/25' },
  BODY_JOLT_TRANSFORM:       { icon: <Code2 className="w-4 h-4" />,    color: 'text-purple-400', bg: 'bg-purple-400/10', border: 'border-purple-400/25' },
  BODY_JSONATA_TRANSFORM:    { icon: <Code2 className="w-4 h-4" />,    color: 'text-violet-400', bg: 'bg-violet-400/10', border: 'border-violet-400/25' },
  CONDITIONAL_ROUTE:         { icon: <GitBranch className="w-4 h-4" />, color: 'text-indigo-400', bg: 'bg-indigo-400/10', border: 'border-indigo-400/25' },
  API_VERSIONING:            { icon: <ToggleLeft className="w-4 h-4" />, color: 'text-cyan-400',  bg: 'bg-cyan-400/10',  border: 'border-cyan-400/25' },
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
  category: 'Core' | 'Auth' | 'Rate Limiting' | 'Resilience' | 'Transformation' | 'Routing'
}

export const PALETTE_NODES: PaletteNodeDef[] = [
  {
    type: 'routeNode',
    label: 'Route Trigger',
    description: 'HTTP path matcher & method filter',
    icon: <Globe className="w-4 h-4" />,
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/20',
    category: 'Core',
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
  },
  {
    type: 'authNode',
    label: 'Auth Filter',
    description: 'JWT / API Key / mTLS authentication',
    icon: <Shield className="w-4 h-4" />,
    color: 'text-blue-400',
    bg: 'bg-blue-400/10',
    border: 'border-blue-400/20',
    category: 'Auth',
  },
  {
    type: 'rateLimitNode',
    label: 'Rate Limit',
    description: 'Token bucket / sliding window throttle',
    icon: <Gauge className="w-4 h-4" />,
    color: 'text-orange-400',
    bg: 'bg-orange-400/10',
    border: 'border-orange-400/20',
    category: 'Rate Limiting',
  },
  {
    type: 'resilienceNode',
    label: 'Resilience',
    description: 'Circuit breaker, retry & timeout',
    icon: <RefreshCw className="w-4 h-4" />,
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    border: 'border-amber-400/20',
    category: 'Resilience',
  },
  {
    type: 'transformNode',
    label: 'Transform',
    description: 'Jolt / JSONata body transformation',
    icon: <Code2 className="w-4 h-4" />,
    color: 'text-purple-400',
    bg: 'bg-purple-400/10',
    border: 'border-purple-400/20',
    category: 'Transformation',
  },
  {
    type: 'conditionalNode',
    label: 'Conditional Route',
    description: 'Branch traffic based on predicates',
    icon: <GitBranch className="w-4 h-4" />,
    color: 'text-indigo-400',
    bg: 'bg-indigo-400/10',
    border: 'border-indigo-400/20',
    category: 'Routing',
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

