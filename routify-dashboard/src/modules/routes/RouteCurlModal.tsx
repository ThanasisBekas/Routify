import { useState, useMemo } from 'react'
import { X, Terminal, Copy, Check, Plus, Trash2, ChevronDown, ChevronUp, AlertTriangle } from 'lucide-react'
import { cn } from '../../lib/utils'
import { useAuthStore } from '../../store/authStore'
import type { RouteDto } from '../../types'
import { METHOD_COLORS } from './routeConstants'

/* ─── helpers ─────────────────────────────────────────────────────────────── */

const GATEWAY_URL_KEY = 'routify.curlModal.gatewayUrl'

function loadGatewayUrl() {
  try {
    return localStorage.getItem(GATEWAY_URL_KEY) ?? 'http://localhost:8080'
  } catch {
    return 'http://localhost:8080'
  }
}
function saveGatewayUrl(v: string) {
  try {
    localStorage.setItem(GATEWAY_URL_KEY, v)
  } catch {
    /* ignore */
  }
}

/* ─── per-filter auth hints ──────────────────────────────────────────────── */

interface KV {
  key: string
  value: string
}

interface AuthHint {
  /** Short label shown in the UI */
  label: string
  /** Human-readable description of what the user must supply */
  note: string
  /** Extra curl flags to append (may be empty) */
  curlFlags: string[]
  /** Extra headers to inject */
  headers: KV[]
  /** Editable input fields the user can fill in the modal */
  fields: { key: string; label: string; placeholder: string; secret?: boolean }[]
}

type AuthValues = Record<string, string>

function buildAuthHint(filterType: string, values: AuthValues): AuthHint {
  switch (filterType) {
    case 'AUTH_JWT':
      return {
        label: 'Bearer JWT',
        note: 'Supply a signed JWT in the Authorization header.',
        curlFlags: [],
        headers: [{ key: 'Authorization', value: `Bearer ${values['token'] ?? '<jwt-token>'}` }],
        fields: [{ key: 'token', label: 'JWT Token', placeholder: 'eyJhbGci...', secret: true }],
      }
    case 'AUTH_BASIC':
      return {
        label: 'Basic Auth',
        note: 'Username and password are Base64-encoded into the Authorization header.',
        curlFlags: [`-u '${values['username'] ?? '<username>'}:${values['password'] ?? '<password>'}'`],
        headers: [],
        fields: [
          { key: 'username', label: 'Username', placeholder: 'username' },
          { key: 'password', label: 'Password', placeholder: 'password', secret: true },
        ],
      }
    case 'AUTH_API_KEY':
      return {
        label: 'API Key',
        note: 'Send your API key in the X-API-Key header (or configure a custom header).',
        curlFlags: [],
        headers: [{ key: 'X-API-Key', value: values['apiKey'] ?? '<your-api-key>' }],
        fields: [{ key: 'apiKey', label: 'API Key', placeholder: 'sk-...', secret: true }],
      }
    case 'AUTH_OAUTH2':
      return {
        label: 'OAuth2 Bearer',
        note: 'Obtain a token from your authorization server and supply it as a Bearer token.',
        curlFlags: [],
        headers: [{ key: 'Authorization', value: `Bearer ${values['token'] ?? '<oauth2-access-token>'}` }],
        fields: [{ key: 'token', label: 'Access Token', placeholder: 'ya29.a0...', secret: true }],
      }
    case 'AUTH_MTLS':
      return {
        label: 'mTLS',
        note: 'Mutual TLS — pass your client certificate and key files to curl.',
        curlFlags: [
          `--cert '${values['certFile'] ?? '/path/to/client.crt'}'`,
          `--key '${values['keyFile'] ?? '/path/to/client.key'}'`,
        ],
        headers: [],
        fields: [
          { key: 'certFile', label: 'Certificate path', placeholder: '/path/to/client.crt' },
          { key: 'keyFile', label: 'Private key path', placeholder: '/path/to/client.key' },
        ],
      }
    case 'AUTH_CLIENT_ID':
      return {
        label: 'Client ID',
        note: 'Send your client ID in the X-Client-Id header.',
        curlFlags: [],
        headers: [{ key: 'X-Client-Id', value: values['clientId'] ?? '<your-client-id>' }],
        fields: [{ key: 'clientId', label: 'Client ID', placeholder: 'client-abc123' }],
      }
    default:
      return { label: '', note: '', curlFlags: [], headers: [], fields: [] }
  }
}

const AUTH_FILTER_TYPES = new Set([
  'AUTH_JWT',
  'AUTH_BASIC',
  'AUTH_API_KEY',
  'AUTH_OAUTH2',
  'AUTH_MTLS',
  'AUTH_CLIENT_ID',
])

/* ─── curl builder ───────────────────────────────────────────────────────── */

function buildCurl(opts: {
  gatewayUrl: string
  method: string
  path: string
  tenantId: string
  extraHeaders: KV[]
  queryParams: KV[]
  body: string
  authHint: AuthHint
  verbose: boolean
}): string {
  const { gatewayUrl, method, path, tenantId, extraHeaders, queryParams, body, authHint, verbose } = opts

  const base = gatewayUrl.replace(/\/$/, '')

  // Build URL
  const qs = queryParams
    .filter((p) => p.key)
    .map((p) => `${encodeURIComponent(p.key)}=${encodeURIComponent(p.value)}`)
    .join('&')
  const url = `${base}${path}${qs ? `?${qs}` : ''}`

  const lines: string[] = [`curl${verbose ? ' -v' : ''} -X ${method}`]

  // Auth flags (e.g. -u for basic)
  for (const flag of authHint.curlFlags) {
    lines.push(`  ${flag}`)
  }

  // Tenant header (always required)
  lines.push(`  -H 'X-Tenant-Id: ${tenantId}'`)

  // Auth headers
  for (const h of authHint.headers) {
    lines.push(`  -H '${h.key}: ${h.value}'`)
  }

  // Extra user headers
  for (const h of extraHeaders.filter((h) => h.key)) {
    lines.push(`  -H '${h.key}: ${h.value}'`)
  }

  // Body
  if (body.trim()) {
    lines.push(`  -H 'Content-Type: application/json'`)
    lines.push(`  -d '${body.replace(/'/g, "'\\''")}'`)
  }

  lines.push(`  '${url}'`)

  return lines.join(' \\\n')
}

/* ─── component ──────────────────────────────────────────────────────────── */

export default function RouteCurlModal({ route, onClose }: { route: RouteDto; onClose: () => void }) {
  const tenantId = useAuthStore((s) => s.user?.tenantId) ?? route.tenantId

  // Gateway URL (persisted)
  const [gatewayUrl, setGatewayUrl] = useState(loadGatewayUrl)

  // Available methods from the route
  const methods = useMemo(
    () =>
      route.methods === '*' ? ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] : route.methods.split(',').map((m) => m.trim()),
    [route.methods],
  )
  const [method, setMethod] = useState(methods[0] ?? 'GET')

  // Path (may contain {param} tokens)
  const [path, setPath] = useState(route.pathPattern.replace(/\*\*$/, '').replace(/\*$/, ''))

  // Detect auth filter(s) on this route
  const authFilters = useMemo(
    () =>
      route.filters.filter((f) => AUTH_FILTER_TYPES.has(f.filterType) && f.enabled).sort((a, b) => a.order - b.order),
    [route.filters],
  )
  const [selectedAuthFilterType, setSelectedAuthFilterType] = useState(authFilters[0]?.filterType ?? 'AUTH_NONE')

  // Per-auth values (token, username, etc.)
  const [authValues, setAuthValues] = useState<AuthValues>({})
  const updateAuth = (key: string, value: string) => setAuthValues((v) => ({ ...v, [key]: value }))

  // Extra headers
  const [extraHeaders, setExtraHeaders] = useState<KV[]>([{ key: '', value: '' }])
  const addHeader = () => setExtraHeaders((h) => [...h, { key: '', value: '' }])
  const removeHeader = (i: number) => setExtraHeaders((h) => h.filter((_, idx) => idx !== i))
  const updateHeader = (i: number, field: 'key' | 'value', val: string) =>
    setExtraHeaders((h) => h.map((item, idx) => (idx === i ? { ...item, [field]: val } : item)))

  // Query params
  const [queryParams, setQueryParams] = useState<KV[]>([])
  const addParam = () => setQueryParams((p) => [...p, { key: '', value: '' }])
  const removeParam = (i: number) => setQueryParams((p) => p.filter((_, idx) => idx !== i))
  const updateParam = (i: number, field: 'key' | 'value', val: string) =>
    setQueryParams((p) => p.map((item, idx) => (idx === i ? { ...item, [field]: val } : item)))

  // Request body
  const [body, setBody] = useState('')

  // Verbose
  const [verbose, setVerbose] = useState(false)

  // Copy state
  const [copied, setCopied] = useState(false)

  // Active section
  const [openSection, setOpenSection] = useState<'auth' | 'headers' | 'params' | 'body' | null>('auth')

  const authHint = buildAuthHint(selectedAuthFilterType, authValues)

  const curl = useMemo(
    () =>
      buildCurl({
        gatewayUrl,
        method,
        path,
        tenantId,
        extraHeaders,
        queryParams,
        body,
        authHint,
        verbose,
      }),
    [gatewayUrl, method, path, tenantId, extraHeaders, queryParams, body, authHint, verbose],
  )

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(curl)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      /* ignore */
    }
  }

  const handleGatewayUrlBlur = () => saveGatewayUrl(gatewayUrl)

  /* ── render ──────────────────────────────────────────────────────────────── */

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.75)', backdropFilter: 'blur(4px)' }}
      onClick={onClose}
    >
      {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions */}
      <div
        role="dialog"
        aria-modal="true"
        aria-label="cURL command builder"
        className="relative w-full max-w-2xl max-h-[92vh] flex flex-col rounded-2xl border border-white/[0.09] bg-[#0d0f15] shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* ── Header ──────────────────────────────────────────────────────── */}
        <div className="flex items-center gap-3 px-6 py-4 border-b border-white/[0.07] shrink-0">
          <div className="w-8 h-8 rounded-xl bg-violet-500/10 border border-violet-500/20 flex items-center justify-center">
            <Terminal className="w-4 h-4 text-violet-400" />
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="text-sm font-bold text-white">cURL Builder</h2>
            <p className="text-xs text-gray-500 truncate">{route.name}</p>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-all"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* ── Scrollable body ──────────────────────────────────────────────── */}
        <div className="flex-1 overflow-y-auto">
          <div className="px-6 py-5 space-y-5">
            {/* Gateway URL */}
            <div>
              <label
                htmlFor="field-gateway-url-0"
                className="block text-[10px] font-semibold text-gray-500 uppercase tracking-widest mb-1.5"
              >
                Gateway URL
              </label>
              <input
                id="field-gateway-url-0"
                value={gatewayUrl}
                onChange={(e) => setGatewayUrl(e.target.value)}
                onBlur={handleGatewayUrlBlur}
                placeholder="http://localhost:8080"
                className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-white font-mono placeholder-gray-600 focus:outline-none focus:border-violet-500 focus:ring-1 focus:ring-violet-500/30 transition-all"
              />
            </div>

            {/* Method + Path */}
            <div className="flex gap-3">
              {/* Method selector */}
              <div className="shrink-0">
                {/* eslint-disable-next-line jsx-a11y/label-has-associated-control */}
                <label className="block text-[10px] font-semibold text-gray-500 uppercase tracking-widest mb-1.5">
                  Method
                </label>
                <div className="flex gap-1.5 flex-wrap">
                  {methods.map((m) => (
                    <button
                      key={m}
                      onClick={() => setMethod(m)}
                      className={cn(
                        'text-[11px] px-2.5 py-1.5 rounded-lg font-mono font-bold border transition-all',
                        method === m
                          ? (METHOD_COLORS[m] ?? METHOD_COLORS['*']) + ' ring-1 ring-offset-0 ring-current'
                          : 'bg-white/[0.03] text-gray-500 border-white/[0.07] hover:text-gray-300',
                      )}
                    >
                      {m}
                    </button>
                  ))}
                </div>
              </div>
              {/* Path */}
              <div className="flex-1 min-w-0">
                <label
                  htmlFor="field-path-1"
                  className="block text-[10px] font-semibold text-gray-500 uppercase tracking-widest mb-1.5"
                >
                  Path
                </label>
                <input
                  id="field-path-1"
                  value={path}
                  onChange={(e) => setPath(e.target.value)}
                  placeholder="/api/v1/..."
                  className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-white font-mono placeholder-gray-600 focus:outline-none focus:border-violet-500 focus:ring-1 focus:ring-violet-500/30 transition-all"
                />
              </div>
            </div>

            {/* Tenant ID (read-only info) */}
            <div className="flex items-center gap-2 px-3 py-2 bg-indigo-500/[0.05] border border-indigo-500/20 rounded-lg">
              <span className="text-[10px] font-bold text-indigo-400 uppercase tracking-wider shrink-0">
                X-Tenant-Id
              </span>
              <code className="text-xs text-indigo-300 font-mono truncate">{tenantId}</code>
              <span className="text-[10px] text-gray-600 ml-auto shrink-0">always injected</span>
            </div>

            {/* ── Auth Section ──────────────────────────────────────────────── */}
            <Accordion
              label="Authentication"
              badge={authHint.label || (authFilters.length === 0 ? 'None' : undefined)}
              badgeColor={authHint.label ? 'emerald' : 'gray'}
              open={openSection === 'auth'}
              onToggle={() => setOpenSection((s) => (s === 'auth' ? null : 'auth'))}
            >
              {authFilters.length === 0 ? (
                <p className="text-xs text-gray-500 italic">No authentication filters on this route.</p>
              ) : (
                <div className="space-y-4">
                  {/* Filter picker if multiple auth filters */}
                  {authFilters.length > 1 && (
                    <div>
                      {/* eslint-disable-next-line jsx-a11y/label-has-associated-control */}
                      <label className="block text-[10px] font-semibold text-gray-500 uppercase tracking-widest mb-1.5">
                        Auth filter
                      </label>
                      <div className="flex gap-1.5 flex-wrap">
                        {authFilters.map((f) => (
                          <button
                            key={f.filterId}
                            onClick={() => {
                              setSelectedAuthFilterType(f.filterType)
                              setAuthValues({})
                            }}
                            className={cn(
                              'text-xs px-2.5 py-1 rounded-lg border font-medium transition-all',
                              selectedAuthFilterType === f.filterType
                                ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-300'
                                : 'bg-white/[0.03] border-white/[0.07] text-gray-500 hover:text-gray-300',
                            )}
                          >
                            {f.filterName}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Auth fields */}
                  {authHint.fields.length > 0 && (
                    <div className="space-y-3">
                      {authHint.fields.map((field) => (
                        <div key={field.key}>
                          <label
                            htmlFor="field-field-label-2"
                            className="block text-[10px] font-semibold text-gray-500 uppercase tracking-widest mb-1.5"
                          >
                            {field.label}
                          </label>
                          <input
                            id="field-field-label-2"
                            type={field.secret ? 'password' : 'text'}
                            value={authValues[field.key] ?? ''}
                            onChange={(e) => updateAuth(field.key, e.target.value)}
                            placeholder={field.placeholder}
                            className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2 text-sm text-white font-mono placeholder-gray-600 focus:outline-none focus:border-violet-500 focus:ring-1 focus:ring-violet-500/30 transition-all"
                          />
                        </div>
                      ))}
                    </div>
                  )}

                  {authHint.note && <p className="text-xs text-gray-500 italic">{authHint.note}</p>}
                </div>
              )}
            </Accordion>

            {/* ── Query Params ─────────────────────────────────────────────── */}
            <Accordion
              label="Query Parameters"
              badge={queryParams.filter((p) => p.key).length || undefined}
              badgeColor="gray"
              open={openSection === 'params'}
              onToggle={() => setOpenSection((s) => (s === 'params' ? null : 'params'))}
            >
              <KVEditor
                rows={queryParams}
                onAdd={addParam}
                onRemove={removeParam}
                onUpdate={updateParam}
                keyPlaceholder="param"
                valuePlaceholder="value"
              />
            </Accordion>

            {/* ── Extra Headers ─────────────────────────────────────────────── */}
            <Accordion
              label="Extra Headers"
              badge={extraHeaders.filter((h) => h.key).length || undefined}
              badgeColor="gray"
              open={openSection === 'headers'}
              onToggle={() => setOpenSection((s) => (s === 'headers' ? null : 'headers'))}
            >
              <KVEditor
                rows={extraHeaders}
                onAdd={addHeader}
                onRemove={removeHeader}
                onUpdate={updateHeader}
                keyPlaceholder="Header-Name"
                valuePlaceholder="value"
              />
            </Accordion>

            {/* ── Request Body ─────────────────────────────────────────────── */}
            {(method === 'POST' || method === 'PUT' || method === 'PATCH') && (
              <Accordion
                label="Request Body"
                badge={body.trim() ? '✓' : undefined}
                badgeColor="gray"
                open={openSection === 'body'}
                onToggle={() => setOpenSection((s) => (s === 'body' ? null : 'body'))}
              >
                <textarea
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                  rows={5}
                  placeholder={'{\n  "key": "value"\n}'}
                  className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2 text-xs text-white font-mono placeholder-gray-600 focus:outline-none focus:border-violet-500 focus:ring-1 focus:ring-violet-500/30 transition-all resize-y"
                />
              </Accordion>
            )}

            {/* ── Options ──────────────────────────────────────────────────── */}
            <div className="flex items-center gap-3">
              {/* eslint-disable-next-line jsx-a11y/label-has-associated-control */}
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <button
                  type="button"
                  role="switch"
                  aria-checked={verbose}
                  onClick={() => setVerbose((v) => !v)}
                  className={cn(
                    'w-8 h-4 rounded-full border transition-all relative',
                    verbose ? 'bg-violet-600 border-violet-500' : 'bg-white/[0.05] border-white/[0.12]',
                  )}
                >
                  <div
                    className={cn(
                      'absolute top-0.5 w-3 h-3 rounded-full bg-white transition-all shadow',
                      verbose ? 'left-4' : 'left-0.5',
                    )}
                  />
                </button>
                <span className="text-xs text-gray-400">
                  Verbose (<code className="font-mono">-v</code>)
                </span>
              </label>
            </div>

            {/* ── Filter chain info ─────────────────────────────────────────── */}
            {route.filters.length > 0 && (
              <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] px-4 py-3">
                <p className="text-[10px] font-bold text-gray-600 uppercase tracking-widest mb-2">
                  Active filter chain
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {[...route.filters]
                    .sort((a, b) => a.order - b.order)
                    .map((f) => (
                      <span
                        key={f.filterId}
                        className={cn(
                          'text-[10px] px-2 py-0.5 rounded-full border font-medium',
                          AUTH_FILTER_TYPES.has(f.filterType)
                            ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-400'
                            : f.filterType.startsWith('RATE_LIMIT')
                              ? 'bg-amber-500/10 border-amber-500/20 text-amber-400'
                              : 'bg-white/[0.04] border-white/[0.08] text-gray-500',
                        )}
                      >
                        {f.filterName}
                      </span>
                    ))}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* ── Generated curl ───────────────────────────────────────────────── */}
        <div className="border-t border-white/[0.07] bg-[#080a0f] shrink-0">
          <div className="flex items-center justify-between px-4 py-2.5 border-b border-white/[0.05]">
            <div className="flex items-center gap-2">
              <Terminal className="w-3.5 h-3.5 text-gray-600" />
              <span className="text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Generated cURL</span>
            </div>
            <button
              onClick={handleCopy}
              className={cn(
                'flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg border font-semibold transition-all',
                copied
                  ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-400'
                  : 'bg-violet-500/10 border-violet-500/20 text-violet-300 hover:bg-violet-500/20',
              )}
            >
              {copied ? <Check className="w-3 h-3" /> : <Copy className="w-3 h-3" />}
              {copied ? 'Copied!' : 'Copy'}
            </button>
          </div>
          <pre className="px-4 py-4 text-xs text-emerald-300 font-mono leading-relaxed overflow-x-auto whitespace-pre max-h-52 scrollbar-thin">
            {curl}
          </pre>
          {route.status !== 'ACTIVE' && (
            <div className="flex items-center gap-2 px-4 py-2.5 border-t border-amber-500/10 bg-amber-500/[0.03]">
              <AlertTriangle className="w-3.5 h-3.5 text-amber-400 shrink-0" />
              <span className="text-xs text-amber-400/80">
                Route is <strong>{route.status}</strong> — it won't match requests until activated.
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/* ─── Accordion ──────────────────────────────────────────────────────────── */

function Accordion({
  label,
  badge,
  badgeColor = 'gray',
  open,
  onToggle,
  children,
}: {
  label: string
  badge?: string | number
  badgeColor?: 'gray' | 'emerald'
  open: boolean
  onToggle: () => void
  children: React.ReactNode
}) {
  return (
    <div className="rounded-xl border border-white/[0.07] overflow-hidden">
      <button
        onClick={onToggle}
        className="w-full flex items-center justify-between px-4 py-3 bg-white/[0.02] hover:bg-white/[0.04] transition-colors"
      >
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-gray-300">{label}</span>
          {badge !== undefined && badge !== 0 && (
            <span
              className={cn(
                'text-[10px] font-bold px-1.5 py-0.5 rounded-full border',
                badgeColor === 'emerald'
                  ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-400'
                  : 'bg-white/[0.06] border-white/[0.10] text-gray-400',
              )}
            >
              {badge}
            </span>
          )}
        </div>
        {open ? (
          <ChevronUp className="w-3.5 h-3.5 text-gray-600" />
        ) : (
          <ChevronDown className="w-3.5 h-3.5 text-gray-600" />
        )}
      </button>
      {open && <div className="px-4 py-4 bg-transparent border-t border-white/[0.05]">{children}</div>}
    </div>
  )
}

/* ─── KV Editor ──────────────────────────────────────────────────────────── */

function KVEditor({
  rows,
  onAdd,
  onRemove,
  onUpdate,
  keyPlaceholder,
  valuePlaceholder,
}: {
  rows: KV[]
  onAdd: () => void
  onRemove: (i: number) => void
  onUpdate: (i: number, field: 'key' | 'value', value: string) => void
  keyPlaceholder: string
  valuePlaceholder: string
}) {
  const inputCls =
    'flex-1 bg-white/[0.04] border border-white/[0.08] rounded-lg px-2.5 py-1.5 text-xs text-white font-mono placeholder-gray-600 focus:outline-none focus:border-violet-500 focus:ring-1 focus:ring-violet-500/20 transition-all'
  return (
    <div className="space-y-2">
      {rows.map((row, i) => (
        <div key={i} className="flex items-center gap-2">
          <input
            value={row.key}
            onChange={(e) => onUpdate(i, 'key', e.target.value)}
            placeholder={keyPlaceholder}
            className={inputCls}
          />
          <span className="text-gray-600 text-xs shrink-0">:</span>
          <input
            value={row.value}
            onChange={(e) => onUpdate(i, 'value', e.target.value)}
            placeholder={valuePlaceholder}
            className={inputCls}
          />
          <button
            onClick={() => onRemove(i)}
            className="p-1 rounded text-gray-600 hover:text-red-400 hover:bg-red-400/10 transition-all shrink-0"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      ))}
      <button
        onClick={onAdd}
        className="flex items-center gap-1.5 text-xs text-gray-500 hover:text-gray-300 transition-colors mt-1"
      >
        <Plus className="w-3.5 h-3.5" />
        Add row
      </button>
    </div>
  )
}
