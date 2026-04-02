import { CheckCircle, Clock, Pause, Archive } from 'lucide-react'
import type { RouteStatus } from '../../types'

// ─── Status config ─────────────────────────────────────────────────────────────

export const STATUS_CONFIG: Record<
  RouteStatus,
  { label: string; color: string; dot: string; glow: string; icon: React.ReactNode }
> = {
  DRAFT:    { label: 'Draft',    color: 'text-amber-400 bg-amber-400/10 border-amber-400/20',       dot: 'bg-amber-400',   glow: 'shadow-amber-500/10',   icon: <Clock className="w-3 h-3" /> },
  ACTIVE:   { label: 'Active',   color: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20', dot: 'bg-emerald-400', glow: 'shadow-emerald-500/15', icon: <CheckCircle className="w-3 h-3" /> },
  DISABLED: { label: 'Disabled', color: 'text-gray-400 bg-gray-400/10 border-gray-400/20',         dot: 'bg-gray-500',    glow: '',                      icon: <Pause className="w-3 h-3" /> },
  ARCHIVED: { label: 'Archived', color: 'text-red-400 bg-red-400/10 border-red-400/20',            dot: 'bg-red-500',     glow: '',                      icon: <Archive className="w-3 h-3" /> },
}

// ─── HTTP Method colours ───────────────────────────────────────────────────────

export const METHOD_COLORS: Record<string, string> = {
  GET:    'bg-blue-500/15 text-blue-300 border-blue-500/20',
  POST:   'bg-green-500/15 text-green-300 border-green-500/20',
  PUT:    'bg-amber-500/15 text-amber-300 border-amber-500/20',
  DELETE: 'bg-red-500/15 text-red-300 border-red-500/20',
  PATCH:  'bg-purple-500/15 text-purple-300 border-purple-500/20',
  '*':    'bg-gray-500/15 text-gray-300 border-gray-500/20',
}

/** Colours used in the create-modal (slightly higher opacity, /20 & /30) */
export const METHOD_COLORS_MODAL: Record<string, string> = {
  GET:    'bg-blue-500/20 text-blue-300 border-blue-500/30',
  POST:   'bg-green-500/20 text-green-300 border-green-500/30',
  PUT:    'bg-amber-500/20 text-amber-300 border-amber-500/30',
  PATCH:  'bg-purple-500/20 text-purple-300 border-purple-500/30',
  DELETE: 'bg-red-500/20 text-red-300 border-red-500/30',
  '*':    'bg-gray-500/20 text-gray-300 border-gray-500/30',
}

// ─── Available HTTP methods ────────────────────────────────────────────────────

export const METHOD_OPTIONS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', '*'] as const

// ─── Flow canvas layout ────────────────────────────────────────────────────────

export const FLOW_COL = {
  client:    0,
  preLabel:  220,
  pre:       220,
  route:     480,
  upstream:  740,
  post:      1000,
  postLabel: 1000,
  response:  1260,
} as const

export const FLOW_ROW_GAP = 100
export const FLOW_START_Y = 160

// ─── Filter status tabs ────────────────────────────────────────────────────────

export const STATUS_FILTER_TABS = ['', 'ACTIVE', 'DRAFT', 'DISABLED'] as const
export type StatusFilterTab = typeof STATUS_FILTER_TABS[number]

