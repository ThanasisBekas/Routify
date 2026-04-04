
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
  upstream:  860,
  post:      1120,
  postLabel: 1120,
  response:  1380,
} as const

export const FLOW_ROW_GAP = 100
export const FLOW_START_Y = 160

// ─── Filter status tabs ────────────────────────────────────────────────────────

export const STATUS_FILTER_TABS = ['', 'ACTIVE', 'DRAFT', 'DISABLED'] as const
export type StatusFilterTab = typeof STATUS_FILTER_TABS[number]

