/**
 * GatewayPage — Full gateway configuration management hub.
 *
 * Tabs:
 *  Overview       — live status, health, loaded routes, circuit breaker states, quick reload
 *  CORS           — origin policies, headers, credentials
 *  Security       — OWASP response headers, CSP, custom headers
 *  Rate Limiting  — global rate limit policies (TOKEN_BUCKET / FIXED_WINDOW / SLIDING_WINDOW)
 *  Resilience     — circuit breaker defaults, retry backoff, timeout, bulkhead
 *  Auth Providers — OAuth2 CC, password grant, introspect, JWT JWKS, Basic
 *  TLS / Certs    — certificate sources, file watchers, SSL bundles, expiry monitor
 *  Networking     — upstream proxy, Reactor Netty HTTP client pool & timeouts
 *  Global Filters — correlation-ID, request logger, security headers toggle, tenant context
 *  Tenant         — multi-tenancy enforcement / isolation
 */

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  Activity, Shield, Globe, Lock, Gauge, RefreshCw, Server,
  Network, AlertTriangle, CheckCircle,
  XCircle, RotateCcw, Plus, Trash2, Edit, Save, X, Eye, EyeOff,
  Settings, Wifi, Link, Route, ShieldCheck, FolderOpen, ExternalLink,
  CheckCircle2, Clock, Key, Layers, Users,
} from 'lucide-react'
import { gatewayApi } from '../../api/gatewayApi'
import { certVaultApi } from '../../api/certVaultApi'
import { routesApi } from '../../api/routesApi'
import { filtersApi } from '../../api/filtersApi'
import { useWsStore } from '../../store/wsStore'
import { useAuthStore } from '../../store/authStore'
import { cn } from '../../lib/utils'
import { Select } from '../../components/ui/Select'
import type {
  GatewayConfig, GatewayCorsConfig, GatewaySecurityHeadersConfig,
  GatewayRateLimitPolicy, GatewayCircuitBreakerDefaults,
  GatewayResilienceDefaults, GatewayAuthProvider,
  GatewayTlsConfig, GatewayProxyConfig,
  GatewayHttpClientConfig, GatewayTenantIsolationConfig,
  FilterSummary, CertGroupDto,
} from '../../types'

// ─── Hook: count filters linked to each gateway config entry ─────────────────

function useLinkedFilterCounts() {
  const { data } = useQuery({
    queryKey: ['filters'],
    queryFn: () => filtersApi.list({ page: 0, size: 200 }),
  })
  const filters: FilterSummary[] = data?.content ?? []

  // Returns count of filters that have a gatewayConfigRef pointing to this id
  const countForRef = (refId: string): number =>
    filters.filter(f => f.gatewayConfigRef?.refId === refId).length

  // Returns a summary list of filters linked to this ref
  const filtersForRef = (refId: string): FilterSummary[] =>
    filters.filter(f => f.gatewayConfigRef?.refId === refId)

  return { countForRef, filtersForRef }
}

// ─── Tab definitions ──────────────────────────────────────────────────────────

type GatewayTab =
  | 'overview' | 'cors' | 'security' | 'rate-limit' | 'resilience'
  | 'auth' | 'tls' | 'networking' | 'tenant'

const TABS: { id: GatewayTab; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
  { id: 'overview',    label: 'Overview',         icon: Activity },
  { id: 'cors',        label: 'CORS',             icon: Globe },
  { id: 'security',    label: 'Security Headers', icon: Shield },
  { id: 'rate-limit',  label: 'Rate Limiting',    icon: Gauge },
  { id: 'resilience',  label: 'Resilience',       icon: RefreshCw },
  { id: 'auth',        label: 'Auth Providers',   icon: Lock },
  { id: 'tls',         label: 'TLS / Certs',      icon: Server },
  { id: 'networking',  label: 'Networking',       icon: Network },
  { id: 'tenant',      label: 'Tenant Isolation', icon: Settings },
]

// ─── Reusable primitives ──────────────────────────────────────────────────────

function SectionHeader({ title, description }: { title: string; description?: string }) {
  return (
    <div className="mb-6">
      <h2 className="text-base font-semibold text-white">{title}</h2>
      {description && <p className="text-sm text-gray-400 mt-0.5">{description}</p>}
    </div>
  )
}

function ToggleRow({
  label, description, checked, onChange,
}: { label: string; description?: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="flex items-center justify-between py-3 border-b border-white/5 last:border-0">
      <div className="mr-4">
        <div className="text-sm text-white">{label}</div>
        {description && <div className="text-xs text-gray-500 mt-0.5">{description}</div>}
      </div>
      <button
        type="button"
        onClick={() => onChange(!checked)}
        className={cn(
          'relative shrink-0 w-9 h-5 rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0a0c10]',
          checked ? 'bg-indigo-600' : 'bg-gray-700',
        )}
      >
        <span className={cn(
          'absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform',
          checked ? 'translate-x-4' : 'translate-x-0',
        )} />
      </button>
    </div>
  )
}

function Field({
  label, children, hint,
}: { label: string; children: React.ReactNode; mono?: boolean; hint?: string }) {
  return (
    <div>
      <label className="block text-xs font-medium text-gray-400 mb-1">{label}</label>
      {children}
      {hint && <p className="text-xs text-gray-600 mt-1">{hint}</p>}
    </div>
  )
}


function SaveBar({ onSave, isPending, dirty }: { onSave: () => void; isPending: boolean; dirty: boolean }) {
  if (!dirty) return null
  return (
    <div className="sticky bottom-0 bg-[#0a0c10]/90 backdrop-blur border-t border-white/10 px-6 py-3 flex items-center justify-end gap-3 z-10">
      <div className="flex items-center gap-2 text-xs text-gray-500">
        <Server className="w-3.5 h-3.5 text-green-500" />
        <span>Saved to <strong className="text-green-400">PostgreSQL</strong> — survives restarts &amp; rollouts</span>
      </div>
      <button
        onClick={onSave}
        disabled={isPending}
        className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
      >
        <Save className="w-4 h-4" />
        {isPending ? 'Saving…' : 'Save & Apply to Gateway'}
      </button>
    </div>
  )
}

function StatusBadge({ state }: { state: string | null | undefined }) {
  const normalized = state ?? 'UNKNOWN'
  const config = {
    CLOSED:    { color: 'text-green-400 bg-green-400/10',   icon: CheckCircle, label: 'Closed' },
    OPEN:      { color: 'text-red-400 bg-red-400/10',       icon: XCircle,     label: 'Open' },
    HALF_OPEN: { color: 'text-yellow-400 bg-yellow-400/10', icon: AlertTriangle, label: 'Half-Open' },
    UP:        { color: 'text-green-400 bg-green-400/10',   icon: CheckCircle, label: 'UP' },
    DOWN:      { color: 'text-red-400 bg-red-400/10',       icon: XCircle,     label: 'DOWN' },
  }[normalized.toUpperCase()] ?? { color: 'text-gray-400 bg-gray-400/10', icon: Activity, label: normalized }

  return (
    <span className={cn('inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full font-medium', config.color)}>
      <config.icon className="w-3 h-3" />
      {config.label}
    </span>
  )
}

// ─── OVERVIEW TAB ─────────────────────────────────────────────────────────────

function OverviewTab({ config }: { config: GatewayConfig }) {
  const qc = useQueryClient()

  // Live metrics pushed every 15s via WebSocket /topic/metrics
  const wsCbStates    = useWsStore(s => s.circuitBreakers)
  const wsHealth      = useWsStore(s => s.gatewayHealth)
  const wsLoadedRoutes = useWsStore(s => s.wsLoadedRoutes)
  const wsStatus      = useWsStore(s => s.status)

  // Always poll gateway status at 30s — WS carries health + CB states but the
  // HTTP status endpoint is the authoritative source for loaded-route count.
  const { data: status } = useQuery({
    queryKey: ['gateway-status'],
    queryFn: gatewayApi.getStatus,
    refetchInterval: 30_000,
  })

  // Cheapest possible active-route count: ask for 1 item, read totalElements.
  // This is always tenant-scoped and reflects DB truth immediately after any
  // activate / deactivate, even before the gateway finishes its reload.
  const { data: activeRoutesPage } = useQuery({
    queryKey: ['active-routes-count'],
    queryFn: () => routesApi.list({ status: 'ACTIVE', page: 0, size: 1 }),
    refetchInterval: 30_000,
    staleTime: 10_000,
  })

  const reloadMutation = useMutation({
    mutationFn: gatewayApi.triggerReload,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['gateway-status'] })
      qc.invalidateQueries({ queryKey: ['active-routes-count'] })
    },
  })

  // Prefer live WS data; fall back to HTTP polled data
  const cbStates = Object.keys(wsCbStates).length > 0
    ? wsCbStates
    : ((status?.circuitBreakers ?? {}) as Record<string, { state: string; failureRate: number; bufferedCalls: number }>)
  const health = wsHealth ?? status?.health

  // Loaded-route count — priority: WS metric > gateway /status > active routes from DB
  const gatewayRouteCount = (status?.routes as any)?.count as number | undefined
  const loadedRoutes =
    wsLoadedRoutes ??          // real-time WS metric (if backend sends it)
    gatewayRouteCount ??       // HTTP /status poll
    activeRoutesPage?.totalElements  // fallback: DB active count

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <SectionHeader
          title="Gateway Overview"
          description="Live status, loaded routes, and circuit breaker health"
        />
        {wsStatus === 'CONNECTED' && (
          <div className="flex items-center gap-1.5 text-xs text-green-400 bg-green-400/10 px-2.5 py-1 rounded-full">
            <Wifi className="w-3 h-3 animate-pulse" />
            Metrics streaming live
          </div>
        )}
      </div>

      {/* Health + Actions */}
      <div className="grid grid-cols-2 gap-4">
        <div className="bg-white/[0.03] rounded-xl p-5 border border-white/5">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs text-gray-400 uppercase tracking-wider">Gateway Health</span>
            <StatusBadge state={health?.status ?? 'UNKNOWN'} />
          </div>
          <div className="space-y-2 text-xs text-gray-500">
            {health?.components && Object.entries(health.components as Record<string, {status:string}>).map(([k, v]) => (
              <div key={k} className="flex justify-between">
                <span className="capitalize">{k}</span>
                <StatusBadge state={(v as any).status ?? 'UNKNOWN'} />
              </div>
            ))}
          </div>
        </div>

        <div className="bg-white/[0.03] rounded-xl p-5 border border-white/5">
          <div className="flex items-center justify-between mb-3">
            <span className="text-xs text-gray-400 uppercase tracking-wider">Loaded Routes</span>
            <Route className="w-4 h-4 text-indigo-400" />
          </div>
          <div className="text-4xl font-bold text-white">
            {loadedRoutes ?? '—'}
          </div>
          <div className="text-xs text-gray-500 mt-1">
            active routes in gateway
            {loadedRoutes !== undefined && wsLoadedRoutes == null && (
              <span className="ml-1.5 text-gray-600">
                {gatewayRouteCount != null ? '(gateway)' : '(db)'}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Circuit Breaker States — live from WebSocket /topic/metrics */}
      <div className="bg-white/[0.03] rounded-xl border border-white/5 overflow-hidden">
        <div className="px-5 py-3 border-b border-white/5 flex items-center justify-between">
          <h3 className="text-sm font-medium text-white">Circuit Breaker States</h3>
          {wsStatus === 'CONNECTED' && (
            <span className="text-[10px] text-green-500">● live</span>
          )}
        </div>
        <div className="divide-y divide-white/5">
          {Object.entries(cbStates).map(([name, state]) => (
            <div key={name} className="flex items-center justify-between px-5 py-3">
              <div>
                <div className="text-sm text-white font-mono">{name}</div>
                <div className="text-xs text-gray-500 mt-0.5">
                  Failure rate: {state.failureRate?.toFixed(1)}% · Buffered: {state.bufferedCalls}
                </div>
              </div>
              <StatusBadge state={state.state} />
            </div>
          ))}
          {Object.keys(cbStates).length === 0 && (
            <div className="px-5 py-4 text-sm text-gray-500">No circuit breaker data available</div>
          )}
        </div>
      </div>

      {/* Config summary */}
      <div className="bg-white/[0.03] rounded-xl border border-white/5 overflow-hidden">
        <div className="px-5 py-3 border-b border-white/5">
          <h3 className="text-sm font-medium text-white">Configuration Summary</h3>
        </div>
        <div className="grid grid-cols-3 gap-px bg-white/5">
          {[
            { label: 'CORS', value: config.cors.enabled ? 'Enabled' : 'Disabled', ok: config.cors.enabled },
            { label: 'Security Headers', value: config.securityHeaders.enabled ? 'Enabled' : 'Disabled', ok: config.securityHeaders.enabled },
            { label: 'Rate Limit Policies', value: `${config.rateLimitPolicies.filter(p => p.enabled).length} active`, ok: true },
            { label: 'Auth Providers', value: `${config.authProviders.filter(p => p.enabled).length} enabled`, ok: config.authProviders.some(p => p.enabled) },
            { label: 'Tenant Isolation', value: config.tenantIsolation.enabled ? 'Enforced' : 'Disabled', ok: config.tenantIsolation.enabled },
          ].map(item => (
            <div key={item.label} className="bg-[#0a0c10] px-5 py-4">
              <div className="text-[10px] text-gray-500 uppercase tracking-wider mb-1">{item.label}</div>
              <div className={cn('text-sm font-medium', item.ok ? 'text-green-400' : 'text-yellow-400')}>
                {item.value}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => reloadMutation.mutate()}
          disabled={reloadMutation.isPending}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm rounded-lg transition-colors"
        >
          <RotateCcw className={cn('w-4 h-4', reloadMutation.isPending && 'animate-spin')} />
          {reloadMutation.isPending ? 'Reloading…' : 'Force Gateway Reload'}
        </button>
        <span className="text-xs text-gray-500">
          Triggers immediate route table refresh — no restart required
        </span>
      </div>

      {config.updatedAt && (
        <p className="text-xs text-gray-600">
          Last config change: {new Date(config.updatedAt).toLocaleString()} by {config.updatedBy ?? 'unknown'}
        </p>
      )}

      {/* Persistence info */}
      <div className="bg-indigo-500/5 border border-indigo-500/20 rounded-xl p-4 text-sm text-gray-400 space-y-2">
        <div className="flex items-center gap-2 font-medium text-indigo-300">
          <Server className="w-4 h-4" />
          Rollout-Safe Persistence
        </div>
        <div className="grid grid-cols-3 gap-4 text-xs mt-2">
          {[
            { step: '1', label: 'Admin saves config', detail: 'Written to PostgreSQL via route-service (durable source of truth)' },
            { step: '2', label: 'Kafka event published', detail: 'GatewayConfigChanged → routify.gateway.config topic via transactional outbox' },
            { step: '3', label: 'All pods reload', detail: 'Every gateway instance reloads config from DB — Redis-independent' },
          ].map(item => (
            <div key={item.step} className="bg-white/[0.03] rounded-lg p-3">
              <div className="text-indigo-400 font-bold text-lg mb-1">{item.step}</div>
              <div className="text-white font-medium mb-1">{item.label}</div>
              <div className="text-gray-600">{item.detail}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

// ─── CORS TAB ─────────────────────────────────────────────────────────────────

function CorsTab({ initial, onSave, isPending }: {
  initial: GatewayCorsConfig
  onSave: (v: GatewayCorsConfig) => void
  isPending: boolean
}) {
  const [cfg, setCfg] = useState<GatewayCorsConfig>(initial)
  const dirty = JSON.stringify(cfg) !== JSON.stringify(initial)

  const listField = (label: string, key: keyof Pick<GatewayCorsConfig, 'allowedOriginPatterns'|'allowedMethods'|'allowedHeaders'|'exposedHeaders'|'paths'>, hint?: string) => (
    <Field label={label} hint={hint}>
      <textarea
        rows={3}
        value={(cfg[key] as string[]).join('\n')}
        onChange={e => setCfg(prev => ({ ...prev, [key]: e.target.value.split('\n').map(s => s.trim()).filter(Boolean) }))}
        className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500 resize-none"
      />
    </Field>
  )

  return (
    <div>
      <SectionHeader title="CORS Configuration" description="Control which origins, methods, and headers are allowed cross-origin" />
      <div className="space-y-5 max-w-2xl">
        <ToggleRow label="CORS Enabled" description="Apply CORS headers to all responses" checked={cfg.enabled} onChange={v => setCfg(p => ({ ...p, enabled: v }))} />
        {listField('Allowed Origin Patterns', 'allowedOriginPatterns', 'One pattern per line. Supports wildcards, e.g. https://*.example.com')}
        {listField('Allowed Methods', 'allowedMethods', 'One HTTP method per line: GET, POST, PUT, DELETE, PATCH, OPTIONS')}
        {listField('Allowed Headers', 'allowedHeaders', 'One header per line. Use * to allow all')}
        {listField('Exposed Headers', 'exposedHeaders', 'Headers accessible to browser JavaScript')}
        {listField('Apply to Paths', 'paths', 'URL path patterns this CORS config applies to. Default: /**')}
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-medium text-gray-400 mb-1">Max Age (seconds)</label>
            <input
              type="number"
              value={cfg.maxAge}
              onChange={e => setCfg(p => ({ ...p, maxAge: Number(e.target.value) }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500"
            />
          </div>
          <div className="flex items-end">
            <ToggleRow label="Allow Credentials" checked={cfg.allowCredentials} onChange={v => setCfg(p => ({ ...p, allowCredentials: v }))} />
          </div>
        </div>
      </div>
      <SaveBar onSave={() => onSave(cfg)} isPending={isPending} dirty={dirty} />
    </div>
  )
}

// ─── SECURITY HEADERS TAB ─────────────────────────────────────────────────────

function SecurityHeadersTab({ initial, onSave, isPending }: {
  initial: GatewaySecurityHeadersConfig
  onSave: (v: GatewaySecurityHeadersConfig) => void
  isPending: boolean
}) {
  const [cfg, setCfg] = useState<GatewaySecurityHeadersConfig>(initial)
  const dirty = JSON.stringify(cfg) !== JSON.stringify(initial)

  return (
    <div>
      <SectionHeader
        title="Security Response Headers"
        description="OWASP-recommended headers injected into every response by the gateway"
      />
      <div className="max-w-2xl space-y-1">
        <ToggleRow label="Enable Security Headers" description="Master toggle — disabling removes all security headers" checked={cfg.enabled} onChange={v => setCfg(p => ({ ...p, enabled: v }))} />

        <div className="pt-4 pb-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Content & Frame Protection</div>
        <ToggleRow label="X-Content-Type-Options: nosniff" description="Prevents MIME-type sniffing attacks" checked={cfg.xContentTypeOptions} onChange={v => setCfg(p => ({ ...p, xContentTypeOptions: v }))} />
        <ToggleRow label="X-Frame-Options" description="Prevents clickjacking via iframes" checked={cfg.xFrameOptions} onChange={v => setCfg(p => ({ ...p, xFrameOptions: v }))} />
        {cfg.xFrameOptions && (
          <div className="ml-4 py-2">
            <label className="block text-xs text-gray-400 mb-1">Value</label>
            <Select
              value={cfg.xFrameOptionsValue}
              onChange={v => setCfg(p => ({ ...p, xFrameOptionsValue: v as 'DENY' | 'SAMEORIGIN' }))}
              options={[
                { value: 'DENY',       label: 'DENY', description: 'Recommended — blocks all iframe embedding' },
                { value: 'SAMEORIGIN', label: 'SAMEORIGIN', description: 'Allows same-origin iframe embedding' },
              ]}
            />
          </div>
        )}
        <ToggleRow label="X-XSS-Protection" description="Legacy XSS filter for older browsers" checked={cfg.xXssProtection} onChange={v => setCfg(p => ({ ...p, xXssProtection: v }))} />

        <div className="pt-4 pb-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Transport Security</div>
        <ToggleRow label="Strict-Transport-Security (HSTS)" description="Enforce HTTPS for this domain" checked={cfg.strictTransportSecurity} onChange={v => setCfg(p => ({ ...p, strictTransportSecurity: v }))} />
        {cfg.strictTransportSecurity && (
          <div className="ml-4 grid grid-cols-3 gap-3 py-2">
            <div>
              <label className="block text-xs text-gray-400 mb-1">Max Age (seconds)</label>
              <input type="number" value={cfg.stsMaxAge} onChange={e => setCfg(p => ({ ...p, stsMaxAge: Number(e.target.value) }))}
                className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
            </div>
            <div className="flex items-end pb-2">
              <ToggleRow label="includeSubDomains" checked={cfg.stsIncludeSubDomains} onChange={v => setCfg(p => ({ ...p, stsIncludeSubDomains: v }))} />
            </div>
            <div className="flex items-end pb-2">
              <ToggleRow label="Preload" checked={cfg.stsPreload} onChange={v => setCfg(p => ({ ...p, stsPreload: v }))} />
            </div>
          </div>
        )}

        <div className="pt-4 pb-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Privacy & Permissions</div>
        <Field label="Referrer-Policy">
          <Select
            value={cfg.referrerPolicy}
            onChange={v => setCfg(p => ({ ...p, referrerPolicy: v }))}
            searchable
            options={[
              'no-referrer',
              'no-referrer-when-downgrade',
              'origin',
              'origin-when-cross-origin',
              'same-origin',
              'strict-origin',
              'strict-origin-when-cross-origin',
              'unsafe-url',
            ].map(v => ({ value: v, label: v }))}
          />
        </Field>

        <Field label="Permissions-Policy" hint="Controls browser feature access">
          <input
            value={cfg.permissionsPolicy}
            onChange={e => setCfg(p => ({ ...p, permissionsPolicy: e.target.value }))}
            className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono text-xs focus:outline-none focus:border-indigo-500"
          />
        </Field>

        <Field label="Content-Security-Policy" hint="Optional CSP header — be careful, may break client apps">
          <input
            value={cfg.contentSecurityPolicy ?? ''}
            onChange={e => setCfg(p => ({ ...p, contentSecurityPolicy: e.target.value || undefined }))}
            placeholder="default-src 'self'; script-src 'self'"
            className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono text-xs focus:outline-none focus:border-indigo-500"
          />
        </Field>

        <div className="pt-4 pb-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Server Fingerprinting</div>
        <ToggleRow label="Remove Server header" description="Removes the upstream server identification header" checked={cfg.removeServerHeader} onChange={v => setCfg(p => ({ ...p, removeServerHeader: v }))} />
        <ToggleRow label="Remove X-Powered-By header" description="Removes framework/technology disclosure" checked={cfg.removePoweredByHeader} onChange={v => setCfg(p => ({ ...p, removePoweredByHeader: v }))} />
      </div>
      <SaveBar onSave={() => onSave(cfg)} isPending={isPending} dirty={dirty} />
    </div>
  )
}

// ─── RATE LIMIT TAB ───────────────────────────────────────────────────────────

function RateLimitTab({ initial, onSave, isPending }: {
  initial: GatewayRateLimitPolicy[]
  onSave: (v: GatewayRateLimitPolicy[]) => void
  isPending: boolean
}) {
  const [policies, setPolicies] = useState<GatewayRateLimitPolicy[]>(initial)
  const [editing, setEditing] = useState<GatewayRateLimitPolicy | null>(null)
  const dirty = JSON.stringify(policies) !== JSON.stringify(initial)
  const { countForRef, filtersForRef } = useLinkedFilterCounts()

  const ALGORITHMS = ['TOKEN_BUCKET', 'FIXED_WINDOW', 'SLIDING_WINDOW'] as const
  const KEY_RESOLVERS = ['IP', 'USER', 'TENANT', 'API_KEY'] as const

  const savePolicy = (p: GatewayRateLimitPolicy) => {
    setPolicies(prev => {
      const list = prev.filter(x => x.id !== p.id)
      return [...list, p]
    })
    setEditing(null)
  }

  const newPolicy = (): GatewayRateLimitPolicy => ({
    id: `rl-${Date.now()}`,
    name: '',
    algorithm: 'TOKEN_BUCKET',
    keyResolver: 'IP',
    replenishRate: 100,
    burstCapacity: 200,
    requestedTokens: 1,
    windowMs: 1000,
    enabled: true,
    globalPaths: [],
  })

  return (
    <div>
      <SectionHeader title="Global Rate Limit Policies" description="Define reusable rate limiting rules applied at the gateway level" />
      <div className="flex justify-end mb-4">
        <button
          onClick={() => setEditing(newPolicy())}
          className="flex items-center gap-2 px-3 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm rounded-lg"
        >
          <Plus className="w-4 h-4" />
          New Policy
        </button>
      </div>

      <div className="space-y-3 max-w-3xl">
        {policies.map(p => {
          const linkedCount = countForRef(p.id)
          const linkedFilters = filtersForRef(p.id)
          return (
          <div key={p.id} className="bg-white/[0.03] rounded-xl border border-white/5 p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className={cn('w-2 h-2 rounded-full', p.enabled ? 'bg-green-400' : 'bg-gray-600')} />
                <div>
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-medium text-white">{p.name || '(unnamed)'}</span>
                    {linkedCount > 0 && (
                      <span
                        title={`Used by: ${linkedFilters.map(f => f.name).join(', ')}`}
                        className="flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-full bg-emerald-500/10 border border-emerald-500/20 text-emerald-400"
                      >
                        <Link className="w-3 h-3" />
                        {linkedCount} filter{linkedCount !== 1 ? 's' : ''}
                      </span>
                    )}
                  </div>
                  <div className="text-xs text-gray-500 mt-0.5">
                    {p.algorithm} · {p.keyResolver} key · {p.replenishRate} req/s burst {p.burstCapacity}
                    {p.globalPaths && p.globalPaths.length > 0 && ` · Paths: ${p.globalPaths.join(', ')}`}
                  </div>
                </div>
              </div>
              <div className="flex gap-2">
                <button onClick={() => setEditing(p)} className="p-1.5 text-gray-400 hover:text-white hover:bg-white/5 rounded-lg">
                  <Edit className="w-4 h-4" />
                </button>
                <button onClick={() => setPolicies(prev => prev.filter(x => x.id !== p.id))}
                  className="p-1.5 text-red-400 hover:bg-red-400/10 rounded-lg">
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            </div>
          </div>
          )
        })}
        {policies.length === 0 && (
          <div className="text-center py-10 text-gray-500 text-sm bg-white/[0.02] rounded-xl">
            No rate limit policies defined — click "New Policy" to create one
          </div>
        )}
      </div>

      {/* Edit modal */}
      {editing && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-[#13151a] border border-white/10 rounded-xl w-full max-w-lg shadow-2xl max-h-[90vh] overflow-y-auto">
            <div className="sticky top-0 bg-[#13151a] flex items-center justify-between px-6 py-4 border-b border-white/10">
              <h3 className="text-base font-semibold text-white">
                {initial.find(p => p.id === editing.id) ? 'Edit Policy' : 'New Policy'}
              </h3>
              <button onClick={() => setEditing(null)} className="p-1 text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
            </div>
            <div className="p-6 space-y-4">
              <Field label="Name">
                <input value={editing.name} onChange={e => setEditing(p => p ? { ...p, name: e.target.value } : p)}
                  className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
              </Field>
              <Field label="Description">
                <input value={editing.description ?? ''} onChange={e => setEditing(p => p ? { ...p, description: e.target.value } : p)}
                  className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
              </Field>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Algorithm">
                  <Select
                    value={editing.algorithm}
                    onChange={v => setEditing(p => p ? { ...p, algorithm: v as GatewayRateLimitPolicy['algorithm'] } : p)}
                    options={ALGORITHMS.map(a => ({ value: a, label: a.replace(/_/g, ' ') }))}
                  />
                </Field>
                <Field label="Key Resolver">
                  <Select
                    value={editing.keyResolver}
                    onChange={v => setEditing(p => p ? { ...p, keyResolver: v as GatewayRateLimitPolicy['keyResolver'] } : p)}
                    options={KEY_RESOLVERS.map(k => ({ value: k, label: k }))}
                  />
                </Field>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Replenish Rate (req/s)">
                  <input type="number" value={editing.replenishRate} onChange={e => setEditing(p => p ? { ...p, replenishRate: Number(e.target.value) } : p)}
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                </Field>
                <Field label="Burst Capacity">
                  <input type="number" value={editing.burstCapacity} onChange={e => setEditing(p => p ? { ...p, burstCapacity: Number(e.target.value) } : p)}
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                </Field>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Requested Tokens">
                  <input type="number" value={editing.requestedTokens} onChange={e => setEditing(p => p ? { ...p, requestedTokens: Number(e.target.value) } : p)}
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                </Field>
                <Field label="Window (ms)" hint="For FIXED/SLIDING algorithms">
                  <input type="number" value={editing.windowMs} onChange={e => setEditing(p => p ? { ...p, windowMs: Number(e.target.value) } : p)}
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                </Field>
              </div>
              <Field label="Global Paths" hint="Apply to these paths globally. Leave empty to make this route-only.">
                <textarea
                  rows={2}
                  value={(editing.globalPaths ?? []).join('\n')}
                  onChange={e => setEditing(p => p ? { ...p, globalPaths: e.target.value.split('\n').map(s => s.trim()).filter(Boolean) } : p)}
                  className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500 resize-none"
                />
              </Field>
              <ToggleRow label="Enabled" checked={editing.enabled} onChange={v => setEditing(p => p ? { ...p, enabled: v } : p)} />
              <div className="flex justify-end gap-3 pt-2">
                <button onClick={() => setEditing(null)} className="px-4 py-2 text-sm text-gray-400 hover:text-white">Cancel</button>
                <button onClick={() => savePolicy(editing)} className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm rounded-lg">Save Policy</button>
              </div>
            </div>
          </div>
        </div>
      )}

      <SaveBar onSave={() => onSave(policies)} isPending={isPending} dirty={dirty} />
    </div>
  )
}

// ─── RESILIENCE TAB ───────────────────────────────────────────────────────────

function ResilienceTab({ initialCb, initialRd, onSaveCb, onSaveRd, isPendingCb, isPendingRd }: {
  initialCb: GatewayCircuitBreakerDefaults
  initialRd: GatewayResilienceDefaults
  onSaveCb: (v: GatewayCircuitBreakerDefaults) => void
  onSaveRd: (v: GatewayResilienceDefaults) => void
  isPendingCb: boolean
  isPendingRd: boolean
}) {
  const [cb, setCb] = useState<GatewayCircuitBreakerDefaults>(initialCb)
  const [rd, setRd] = useState<GatewayResilienceDefaults>(initialRd)
  const dirtyCb = JSON.stringify(cb) !== JSON.stringify(initialCb)
  const dirtyRd = JSON.stringify(rd) !== JSON.stringify(initialRd)

  return (
    <div className="space-y-8">
      {/* Circuit Breaker */}
      <div>
        <SectionHeader title="Circuit Breaker Defaults" description="Default Resilience4J settings applied when a route uses CIRCUIT_BREAKER filter without explicit overrides" />
        <div className="grid grid-cols-2 gap-4 max-w-2xl">
          <Field label="Sliding Window Type">
            <Select
              value={cb.slidingWindowType}
              onChange={v => setCb(p => ({ ...p, slidingWindowType: v as 'COUNT_BASED' | 'TIME_BASED' }))}
              options={[
                { value: 'COUNT_BASED', label: 'COUNT_BASED', description: 'Window defined by number of calls' },
                { value: 'TIME_BASED',  label: 'TIME_BASED',  description: 'Window defined by time duration' },
              ]}
            />
          </Field>
          <Field label="Sliding Window Size">
            <input type="number" value={cb.slidingWindowSize} onChange={e => setCb(p => ({ ...p, slidingWindowSize: Number(e.target.value) }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Failure Rate Threshold (%)" hint="Opens circuit when exceeded">
            <input type="number" min={0} max={100} value={cb.failureRateThreshold} onChange={e => setCb(p => ({ ...p, failureRateThreshold: Number(e.target.value) }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Wait in Open State" hint="e.g. 10s, 1m">
            <input value={cb.waitDurationInOpenState} onChange={e => setCb(p => ({ ...p, waitDurationInOpenState: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Permitted Calls in Half-Open">
            <input type="number" value={cb.permittedNumberOfCallsInHalfOpenState} onChange={e => setCb(p => ({ ...p, permittedNumberOfCallsInHalfOpenState: Number(e.target.value) }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Minimum Calls">
            <input type="number" value={cb.minimumNumberOfCalls} onChange={e => setCb(p => ({ ...p, minimumNumberOfCalls: Number(e.target.value) }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Default Fallback URI">
            <input value={cb.fallbackUri} onChange={e => setCb(p => ({ ...p, fallbackUri: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono text-xs focus:outline-none focus:border-indigo-500" />
          </Field>
        </div>
        <div className="mt-3 space-y-1 max-w-2xl">
          <ToggleRow label="Auto-transition from OPEN to HALF-OPEN" checked={cb.automaticTransitionFromOpenToHalfOpen} onChange={v => setCb(p => ({ ...p, automaticTransitionFromOpenToHalfOpen: v }))} />
        </div>
        {dirtyCb && (
          <div className="mt-4">
            <button onClick={() => onSaveCb(cb)} disabled={isPendingCb}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm rounded-lg">
              <Save className="w-4 h-4" />
              {isPendingCb ? 'Saving…' : 'Apply Circuit Breaker Defaults'}
            </button>
          </div>
        )}
      </div>

      {/* Retry / Timeout / Bulkhead */}
      <div>
        <SectionHeader title="Retry, Timeout & Bulkhead Defaults" description="Default resilience settings for retry policies, request timeouts, and concurrency limits" />
        <div className="grid grid-cols-2 gap-4 max-w-2xl">
          <Field label="Max Retry Attempts">
            <input type="number" value={rd.retryMaxAttempts} onChange={e => setRd(p => ({ ...p, retryMaxAttempts: Number(e.target.value) }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Retry Wait Duration" hint="e.g. 500ms, 1s">
            <input value={rd.retryWaitDuration} onChange={e => setRd(p => ({ ...p, retryWaitDuration: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Max Wait Duration" hint="Exponential backoff cap">
            <input value={rd.retryMaxWaitDuration} onChange={e => setRd(p => ({ ...p, retryMaxWaitDuration: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Backoff Multiplier" hint="Applied when exponential backoff is enabled">
            <input type="number" step={0.1} value={rd.retryExponentialMultiplier} onChange={e => setRd(p => ({ ...p, retryExponentialMultiplier: Number(e.target.value) }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Timeout Duration" hint="e.g. 10s, 30s">
            <input value={rd.timeoutDuration} onChange={e => setRd(p => ({ ...p, timeoutDuration: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Bulkhead Max Concurrent Calls">
            <input type="number" value={rd.bulkheadMaxConcurrentCalls} onChange={e => setRd(p => ({ ...p, bulkheadMaxConcurrentCalls: Number(e.target.value) }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
          </Field>
        </div>
        <div className="mt-3 space-y-1 max-w-2xl">
          <ToggleRow label="Exponential Backoff on Retry" checked={rd.retryExponentialBackoff} onChange={v => setRd(p => ({ ...p, retryExponentialBackoff: v }))} />
          <ToggleRow label="Cancel Running Future on Timeout" checked={rd.timeoutCancelRunningFuture} onChange={v => setRd(p => ({ ...p, timeoutCancelRunningFuture: v }))} />
          <ToggleRow label="Bulkhead Enabled" description="Limit maximum concurrent calls per route" checked={rd.bulkheadEnabled} onChange={v => setRd(p => ({ ...p, bulkheadEnabled: v }))} />
        </div>
        {dirtyRd && (
          <div className="mt-4">
            <button onClick={() => onSaveRd(rd)} disabled={isPendingRd}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm rounded-lg">
              <Save className="w-4 h-4" />
              {isPendingRd ? 'Saving…' : 'Apply Resilience Defaults'}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

// ─── AUTH PROVIDERS TAB ───────────────────────────────────────────────────────

/** Sentinel value the server returns for masked secrets — never write it back. */
const SECRET_MASK = '••••••••'

/** Strip masked-secret placeholders before sending to the API. */
function stripMaskedSecrets(p: GatewayAuthProvider): GatewayAuthProvider {
  return {
    ...p,
    clientSecret: p.clientSecret === SECRET_MASK ? undefined : p.clientSecret,
    password:     p.password     === SECRET_MASK ? undefined : p.password,
  }
}

function AuthProvidersTab({ initial, onUpsert, onDelete, isPending }: {
  initial: GatewayAuthProvider[]
  onUpsert: (p: GatewayAuthProvider) => void
  onDelete: (id: string) => void
  isPending: boolean
}) {
  const [editing, setEditing] = useState<GatewayAuthProvider | null>(null)
  const [showSecret, setShowSecret] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const { countForRef, filtersForRef } = useLinkedFilterCounts()

  const TYPES = [
    { value: 'JWT_VERIFY',              label: 'JWT Verification (JWKS)' },
    { value: 'OAUTH2_CLIENT_CREDENTIALS', label: 'OAuth2 Client Credentials' },
    { value: 'OAUTH2_PASSWORD',          label: 'OAuth2 Password Grant' },
    { value: 'OAUTH2_INTROSPECT',        label: 'OAuth2 Token Introspection' },
    { value: 'BASIC',                    label: 'Basic Auth' },
  ]

  const newProvider = (): GatewayAuthProvider => ({
    id: `ap-${Date.now()}`,
    name: '',
    type: 'JWT_VERIFY',
    enabled: true,
  })

  const openEditor = (p: GatewayAuthProvider) => {
    setShowSecret(false)
    setShowPassword(false)
    setEditing(p)
  }

  return (
    <div>
      <SectionHeader title="Auth Providers" description="Configure upstream authentication and token verification providers used by filter definitions" />
      <div className="flex justify-end mb-4">
        <button onClick={() => openEditor(newProvider())}
          className="flex items-center gap-2 px-3 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm rounded-lg">
          <Plus className="w-4 h-4" /> Add Provider
        </button>
      </div>

      <div className="space-y-3 max-w-3xl">
        {initial.map(p => {
          const linkedCount = countForRef(p.id)
          const linkedFilters = filtersForRef(p.id)
          return (
          <div key={p.id} className="bg-white/[0.03] rounded-xl border border-white/5 p-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className={cn('w-2 h-2 rounded-full', p.enabled ? 'bg-green-400' : 'bg-gray-600')} />
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-white">{p.name}</span>
                    <span className="text-xs bg-indigo-500/20 text-indigo-300 px-1.5 py-0.5 rounded font-mono">{p.type}</span>
                    {linkedCount > 0 && (
                      <span
                        title={`Used by: ${linkedFilters.map(f => f.name).join(', ')}`}
                        className="flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-full bg-emerald-500/10 border border-emerald-500/20 text-emerald-400"
                      >
                        <Link className="w-3 h-3" />
                        {linkedCount} filter{linkedCount !== 1 ? 's' : ''}
                      </span>
                    )}
                  </div>
                  <div className="text-xs text-gray-500 mt-0.5 font-mono">
                    {p.jwksUri ?? p.uri ?? (p.username ? `user: ${p.username}` : '—')}
                  </div>
                </div>
              </div>
              <div className="flex gap-2">
                <button onClick={() => openEditor(p)} className="p-1.5 text-gray-400 hover:text-white hover:bg-white/5 rounded-lg"><Edit className="w-4 h-4" /></button>
                <button onClick={() => { if (confirm(`Delete provider "${p.name}"?`)) onDelete(p.id) }}
                  className="p-1.5 text-red-400 hover:bg-red-400/10 rounded-lg"><Trash2 className="w-4 h-4" /></button>
              </div>
            </div>
          </div>
          )
        })}
        {initial.length === 0 && (
          <div className="text-center py-10 text-gray-500 text-sm bg-white/[0.02] rounded-xl">
            No auth providers configured
          </div>
        )}
      </div>

      {editing && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-[#13151a] border border-white/10 rounded-xl w-full max-w-lg shadow-2xl max-h-[90vh] overflow-y-auto">
            <div className="sticky top-0 bg-[#13151a] flex items-center justify-between px-6 py-4 border-b border-white/10">
              <h3 className="text-base font-semibold text-white">Auth Provider</h3>
              <button onClick={() => setEditing(null)} className="p-1 text-gray-400 hover:text-white"><X className="w-5 h-5" /></button>
            </div>
            <div className="p-6 space-y-4">
              <Field label="Name">
                <input value={editing.name} onChange={e => setEditing(p => p ? { ...p, name: e.target.value } : p)}
                  className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
              </Field>
              <Field label="Type">
                <Select
                  value={editing.type}
                  onChange={v => setEditing(p => p ? { ...p, type: v as GatewayAuthProvider['type'] } : p)}
                  options={TYPES.map(t => ({ value: t.value, label: t.label }))}
                />
              </Field>

              {editing.type === 'JWT_VERIFY' && (
                <>
                  <Field label="JWKS URI">
                    <input value={editing.jwksUri ?? ''} onChange={e => setEditing(p => p ? { ...p, jwksUri: e.target.value } : p)}
                      placeholder="https://auth.example.com/.well-known/jwks.json"
                      className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
                  </Field>
                  <div className="grid grid-cols-2 gap-4">
                    <Field label="Expected Issuer">
                      <input value={editing.issuer ?? ''} onChange={e => setEditing(p => p ? { ...p, issuer: e.target.value } : p)}
                        className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                    </Field>
                    <Field label="Expected Audience">
                      <input value={editing.audience ?? ''} onChange={e => setEditing(p => p ? { ...p, audience: e.target.value } : p)}
                        className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                    </Field>
                  </div>
                  <Field label="Algorithm">
                    <Select
                      value={editing.algorithm ?? 'RS256'}
                      onChange={v => setEditing(p => p ? { ...p, algorithm: v } : p)}
                      options={['RS256','RS384','RS512','HS256','ES256'].map(a => ({ value: a, label: a }))}
                    />
                  </Field>
                </>
              )}

              {/* OAuth2: shared fields (token URI + client credentials) */}
              {['OAUTH2_CLIENT_CREDENTIALS','OAUTH2_PASSWORD','OAUTH2_INTROSPECT'].includes(editing.type) && (
                <>
                  <Field label={editing.type === 'OAUTH2_INTROSPECT' ? 'Introspection Endpoint URI' : 'Token Endpoint URI'}>
                    <input value={editing.uri ?? ''} onChange={e => setEditing(p => p ? { ...p, uri: e.target.value } : p)}
                      placeholder={editing.type === 'OAUTH2_INTROSPECT' ? 'https://auth.example.com/oauth/introspect' : 'https://auth.example.com/oauth/token'}
                      className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
                  </Field>
                  <div className="grid grid-cols-2 gap-4">
                    <Field label="Client ID">
                      <input value={editing.clientId ?? ''} onChange={e => setEditing(p => p ? { ...p, clientId: e.target.value } : p)}
                        className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                    </Field>
                    <Field label="Client Secret" hint={editing.clientSecret === SECRET_MASK ? 'Stored — leave blank to keep unchanged' : undefined}>
                      <div className="relative">
                        <input
                          type={showSecret ? 'text' : 'password'}
                          value={editing.clientSecret ?? ''}
                          placeholder={editing.clientSecret === SECRET_MASK ? '(unchanged)' : ''}
                          onChange={e => setEditing(p => p ? { ...p, clientSecret: e.target.value } : p)}
                          className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 pr-8 text-sm text-white focus:outline-none focus:border-indigo-500" />
                        <button type="button" onClick={() => setShowSecret(s => !s)} className="absolute right-2 top-2.5 text-gray-400">
                          {showSecret ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                        </button>
                      </div>
                    </Field>
                  </div>
                  {editing.type !== 'OAUTH2_INTROSPECT' && (
                    <Field label="Scope">
                      <input value={editing.scope ?? ''} onChange={e => setEditing(p => p ? { ...p, scope: e.target.value } : p)}
                        placeholder="read write"
                        className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                    </Field>
                  )}
                </>
              )}

              {/* OAuth2 Password Grant: resource-owner credentials */}
              {editing.type === 'OAUTH2_PASSWORD' && (
                <>
                  <div className="pt-1 pb-0.5 text-xs font-semibold text-gray-500 uppercase tracking-wider">Resource-Owner Credentials</div>
                  <div className="grid grid-cols-2 gap-4">
                    <Field label="Username">
                      <input value={editing.username ?? ''} onChange={e => setEditing(p => p ? { ...p, username: e.target.value } : p)}
                        className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                    </Field>
                    <Field label="Password" hint={editing.password === SECRET_MASK ? 'Stored — leave blank to keep unchanged' : undefined}>
                      <div className="relative">
                        <input
                          type={showPassword ? 'text' : 'password'}
                          value={editing.password ?? ''}
                          placeholder={editing.password === SECRET_MASK ? '(unchanged)' : ''}
                          onChange={e => setEditing(p => p ? { ...p, password: e.target.value } : p)}
                          className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 pr-8 text-sm text-white focus:outline-none focus:border-indigo-500" />
                        <button type="button" onClick={() => setShowPassword(s => !s)} className="absolute right-2 top-2.5 text-gray-400">
                          {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                        </button>
                      </div>
                    </Field>
                  </div>
                </>
              )}

              {/* OAuth2 Introspect: token parameter style */}
              {editing.type === 'OAUTH2_INTROSPECT' && (
                <>
                  <div className="grid grid-cols-2 gap-4">
                    <Field label="Parameter Style" hint="How the token is sent to the introspection endpoint">
                      <Select
                        value={editing.parameterStyle ?? 'BODY'}
                        onChange={v => setEditing(p => p ? { ...p, parameterStyle: v as 'BODY' | 'HEADER' } : p)}
                        options={[
                          { value: 'BODY',   label: 'BODY',   description: 'token= form field in request body' },
                          { value: 'HEADER', label: 'HEADER', description: 'Authorization header bearer token' },
                        ]}
                      />
                    </Field>
                    <Field label="Parameter Name" hint="Default: token">
                      <input value={editing.parameterName ?? ''} onChange={e => setEditing(p => p ? { ...p, parameterName: e.target.value } : p)}
                        placeholder="token"
                        className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
                    </Field>
                  </div>
                </>
              )}

              {/* Basic Auth provider */}
              {editing.type === 'BASIC' && (
                <div className="grid grid-cols-2 gap-4">
                  <Field label="Username">
                    <input value={editing.username ?? ''} onChange={e => setEditing(p => p ? { ...p, username: e.target.value } : p)}
                      className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                  </Field>
                  <Field label="Password" hint={editing.password === SECRET_MASK ? 'Stored — leave blank to keep unchanged' : undefined}>
                    <div className="relative">
                      <input
                        type={showPassword ? 'text' : 'password'}
                        value={editing.password ?? ''}
                        placeholder={editing.password === SECRET_MASK ? '(unchanged)' : ''}
                        onChange={e => setEditing(p => p ? { ...p, password: e.target.value } : p)}
                        className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 pr-8 text-sm text-white focus:outline-none focus:border-indigo-500" />
                      <button type="button" onClick={() => setShowPassword(s => !s)} className="absolute right-2 top-2.5 text-gray-400">
                        {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </Field>
                </div>
              )}

              <ToggleRow label="Enabled" checked={editing.enabled} onChange={v => setEditing(p => p ? { ...p, enabled: v } : p)} />
              <div className="flex justify-end gap-3 pt-2">
                <button onClick={() => setEditing(null)} className="px-4 py-2 text-sm text-gray-400 hover:text-white">Cancel</button>
                <button
                  onClick={() => { onUpsert(stripMaskedSecrets(editing)); setEditing(null) }}
                  disabled={isPending}
                  className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm rounded-lg">
                  {isPending ? 'Saving…' : 'Save Provider'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── TLS / CERTIFICATES TAB ───────────────────────────────────────────────────

function TlsTab({ initial, onSave, isPending }: {
  initial: GatewayTlsConfig
  onSave: (v: GatewayTlsConfig) => void
  isPending: boolean
}) {
  const [cfg, setCfg] = useState<GatewayTlsConfig>(initial)
  const dirty = JSON.stringify(cfg) !== JSON.stringify(initial)
  const { user } = useAuthStore()
  const tenantId = user?.tenantId ?? ''
  const navigate = useNavigate()

  const certStatusColor: Record<string, string> = {
    VALID:          'text-green-400',
    EXPIRING_SOON:  'text-yellow-400',
    EXPIRED:        'text-red-400',
  }

  // ── Live registry from gateway actuator ──────────────────────────────────
  const { data: liveRegistry, isLoading: liveLoading, refetch: refetchLive } = useQuery({
    queryKey: ['gateway-live-certs'],
    queryFn: gatewayApi.getLiveCertificates,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })

  // ── Cert groups mapped to gateway TLS ────────────────────────────────────
  const { data: groupsPage, isLoading: groupsLoading, refetch: refetchGroups } = useQuery({
    queryKey: ['cert-groups', tenantId, 'ACTIVE'],
    queryFn:  () => certVaultApi.listGroups({ tenantId, status: 'ACTIVE', size: 100 }),
    enabled:  !!tenantId,
    staleTime: 30_000,
  })

  const activeGroups: CertGroupDto[] = groupsPage?.content ?? []

  // Fetch detail (with members) for each active group in parallel
  const groupDetailsQueries = useQuery({
    queryKey: ['cert-groups-details-tls', tenantId, activeGroups.map(g => g.id).join(',')],
    queryFn:  async () => {
      if (activeGroups.length === 0) return []
      return Promise.all(activeGroups.map(g => certVaultApi.getGroup(g.id, tenantId)))
    },
    enabled:  !!tenantId && activeGroups.length > 0,
    staleTime: 30_000,
  })
  const groupsWithMembers: CertGroupDto[] = (groupDetailsQueries.data as CertGroupDto[] | undefined) ?? activeGroups

  // Cast liveRegistry to a shape we can iterate
  const liveEntries = liveRegistry as Record<string, { status?: string; fingerprint?: string; notAfter?: string; source?: string }> | undefined

  const expiryStatusIcon = (status?: string) => {
    if (status === 'EXPIRING_SOON') return <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />
    if (status === 'EXPIRED')       return <XCircle       className="w-3.5 h-3.5 text-red-400" />
    return                                  <CheckCircle2  className="w-3.5 h-3.5 text-emerald-400" />
  }

  return (
    <div>
      <SectionHeader
        title="TLS / Certificate Store"
        description="Manage certificate file sources, directory watchers, and the Certificate Vault integration for mTLS hot-reload"
      />
      <div className="max-w-3xl space-y-8">

        {/* ── Timing settings ─────────────────────────────────────────────────── */}
        <div className="grid grid-cols-2 gap-4">
          <Field label="Expiry Warning Threshold" hint="e.g. 30d — alert when cert expires within this window">
            <input value={cfg.expiryWarning} onChange={e => setCfg(p => ({ ...p, expiryWarning: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="File Watch Interval" hint="e.g. 30s — how often to check for cert changes on disk">
            <input value={cfg.fileWatchInterval} onChange={e => setCfg(p => ({ ...p, fileWatchInterval: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
          </Field>
        </div>

        {/* ── Certificate Vault Panel ──────────────────────────────────────────── */}
        <div>
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <ShieldCheck className="w-4 h-4 text-indigo-400" />
              <h3 className="text-sm font-semibold text-white">Certificate Vault — Gateway Mappings</h3>
              <span className="text-[10px] text-gray-500 px-2 py-0.5 rounded-full bg-white/[0.04] border border-white/[0.06]">
                groups
              </span>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={() => { refetchGroups(); refetchLive(); groupDetailsQueries.refetch() }}
                className="flex items-center gap-1 text-xs text-gray-400 hover:text-white px-2 py-1 rounded-md hover:bg-white/5 transition-colors"
              >
                <RotateCcw className="w-3 h-3" />
                Refresh
              </button>
              <button
                onClick={() => navigate('/certificates')}
                className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300 px-2 py-1 rounded-md hover:bg-indigo-500/10 transition-colors"
              >
                <ExternalLink className="w-3 h-3" />
                Manage Vault
              </button>
            </div>
          </div>

          {/* Info banner */}
          <div className="mb-3 p-3 rounded-lg bg-indigo-500/5 border border-indigo-500/20 text-xs text-indigo-300/80 leading-relaxed">
            <span className="font-semibold text-indigo-300">How it works: </span>
            Each <span className="text-violet-300 font-medium">certificate group</span> owns a stable logical ID that the gateway TLS registry keys on.
            Upload certificates into a group — the gateway loads them automatically under the group's logical ID.
            Rotate by adding a new cert to the group; the old one can then be revoked with no downtime.
          </div>

          {groupsLoading ? (
            <div className="flex items-center gap-2 py-6 text-sm text-gray-500">
              <div className="w-4 h-4 border border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
              Loading certificate groups…
            </div>
          ) : activeGroups.length === 0 ? (
            <div className="rounded-xl border border-dashed border-white/10 bg-white/[0.015] py-8 flex flex-col items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-violet-500/10 border border-violet-500/20 flex items-center justify-center">
                <Layers className="w-5 h-5 text-violet-500/50" />
              </div>
              <div className="text-center">
                <p className="text-sm font-medium text-gray-400">No certificate groups configured</p>
                <p className="text-xs text-gray-600 mt-1">
                  Go to <span className="text-indigo-400">Certificate Vault</span> → create a group with a logical ID → upload certs into it
                </p>
              </div>
              <button
                onClick={() => navigate('/certificates')}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-xs font-medium rounded-lg transition-all"
              >
                <ExternalLink className="w-3.5 h-3.5" />
                Open Certificate Vault
              </button>
            </div>
          ) : (
            <div className="space-y-3">
              {groupsWithMembers.map(group => {
                // Match the group's logicalId against the live gateway registry
                const liveEntry    = liveEntries?.[group.logicalId]
                const isLoadedInGw = liveEntry != null

                return (
                  <div
                    key={group.id}
                    className="bg-white/[0.03] rounded-xl border border-white/[0.06] p-4"
                  >
                    {/* Group header row */}
                    <div className="flex items-start justify-between gap-4">
                      {/* Left: group identity */}
                      <div className="flex items-start gap-3 min-w-0">
                        <div className="mt-0.5 w-8 h-8 rounded-lg bg-violet-500/10 border border-violet-500/20 flex items-center justify-center shrink-0">
                          <Layers className="w-4 h-4 text-violet-400" />
                        </div>
                        <div className="min-w-0">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="text-sm font-semibold text-white truncate">{group.alias}</span>
                            <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] bg-violet-500/10 border border-violet-500/20 text-violet-300 font-mono">
                              {group.logicalId}
                            </span>
                          </div>
                          {group.description && (
                            <p className="text-[11px] text-gray-600 mt-0.5 truncate max-w-xs">{group.description}</p>
                          )}
                          {/* Member count */}
                          <div className="flex items-center gap-1 mt-1">
                            <Users className="w-2.5 h-2.5 text-gray-500" />
                            <span className="text-[10px] text-gray-500">
                              {group.memberCount} certificate{group.memberCount !== 1 ? 's' : ''}
                            </span>
                          </div>
                        </div>
                      </div>

                      {/* Right: status column */}
                      <div className="flex flex-col items-end gap-1.5 shrink-0">
                        {/* Expiry health */}
                        <div className="flex items-center gap-1">
                          {expiryStatusIcon(group.expiryHealthStatus)}
                          <span className="text-[11px] text-gray-500">
                            {group.expiryHealthStatus === 'VALID'         ? 'All valid' :
                             group.expiryHealthStatus === 'EXPIRING_SOON' ? 'Expiring soon' : 'Expired'}
                          </span>
                        </div>
                        {/* Gateway load status */}
                        {isLoadedInGw ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium bg-violet-500/10 border border-violet-500/20 text-violet-300">
                            <CheckCircle className="w-2.5 h-2.5" />
                            loaded in gateway
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium bg-gray-500/10 border border-gray-500/20 text-gray-500">
                            <Clock className="w-2.5 h-2.5" />
                            pending load
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Gateway TLS logical ID binding row */}
                    <div className="mt-3 pt-3 border-t border-white/[0.04] flex items-center gap-2 flex-wrap">
                      <Link className="w-3.5 h-3.5 text-indigo-400 shrink-0" />
                      <span className="text-xs text-gray-400">Gateway TLS key:</span>
                      <code className="text-xs text-indigo-300 font-mono bg-indigo-500/10 px-2 py-0.5 rounded">
                        {group.logicalId}
                      </code>
                      {liveEntry?.notAfter && (
                        <span className="text-[10px] text-gray-500">
                          expires {new Date(liveEntry.notAfter).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })}
                        </span>
                      )}
                      {liveEntry?.source && (
                        <span className="ml-auto text-[10px] text-gray-600 font-mono" title="Source tag in gateway registry">
                          {liveEntry.source}
                        </span>
                      )}
                    </div>

                    {/* Member certs (if loaded via detail) */}
                    {group.members && group.members.length > 0 && (
                      <div className="mt-3 pt-3 border-t border-white/[0.04] space-y-1.5">
                        {group.members.filter(m => m.status === 'ACTIVE').map(cert => (
                          <div key={cert.id} className="flex items-center gap-2 text-xs text-gray-500">
                            <ShieldCheck className="w-3 h-3 text-indigo-400/60 shrink-0" />
                            <span className="text-white/70 truncate">{cert.alias}</span>
                            {cert.memberAlias && (
                              <span className="text-[10px] text-violet-400 bg-violet-500/10 px-1.5 py-0.5 rounded font-medium">{cert.memberAlias}</span>
                            )}
                            {cert.hasPrivateKey && (
                              <span className="inline-flex items-center gap-0.5 text-[10px] text-emerald-400">
                                <Key className="w-2.5 h-2.5" /> key
                              </span>
                            )}
                            {cert.notAfter && (
                              <span className="ml-auto text-[10px] text-gray-600 shrink-0">
                                exp. {new Date(cert.notAfter).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })}
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {/* ── Live Gateway Certificate Registry ───────────────────────────────── */}
        <div>
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <Server className="w-4 h-4 text-gray-400" />
              <h3 className="text-sm font-semibold text-white">Live Gateway Registry</h3>
              <span className="text-[10px] text-gray-500 px-2 py-0.5 rounded-full bg-white/[0.04] border border-white/[0.06]">
                in-memory
              </span>
            </div>
            <button
              onClick={() => refetchLive()}
              disabled={liveLoading}
              className="flex items-center gap-1 text-xs text-gray-400 hover:text-white px-2 py-1 rounded-md hover:bg-white/5 transition-colors"
            >
              <RotateCcw className={cn('w-3 h-3', liveLoading && 'animate-spin')} />
              Refresh
            </button>
          </div>

          {liveLoading ? (
            <div className="flex items-center gap-2 py-4 text-sm text-gray-500">
              <div className="w-4 h-4 border border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
              Fetching live registry…
            </div>
          ) : liveEntries && Object.keys(liveEntries).length > 0 ? (
            <div className="rounded-xl border border-white/[0.06] overflow-hidden">
              <table className="w-full text-xs">
                <thead>
                  <tr className="bg-white/[0.02] border-b border-white/[0.06]">
                    <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-wider">Logical ID</th>
                    <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-wider">Fingerprint</th>
                    <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-wider">Expiry</th>
                    <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-wider">Source</th>
                    <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-wider">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/[0.04]">
                  {Object.entries(liveEntries).map(([logicalId, entry]) => (
                    <tr key={logicalId} className="hover:bg-white/[0.02] transition-colors">
                      <td className="px-4 py-2.5 font-mono text-white">{logicalId}</td>
                      <td className="px-4 py-2.5 font-mono text-gray-500 truncate max-w-[160px]" title={entry.fingerprint}>
                        {entry.fingerprint ? entry.fingerprint.slice(0, 20) + '…' : '—'}
                      </td>
                      <td className="px-4 py-2.5 text-gray-400">
                        {entry.notAfter ? new Date(entry.notAfter).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }) : '—'}
                      </td>
                      <td className="px-4 py-2.5 font-mono text-gray-500 truncate max-w-[120px]" title={entry.source}>
                        {entry.source ?? '—'}
                      </td>
                      <td className="px-4 py-2.5">
                        {entry.status ? (
                          <span className={cn(
                            'inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium',
                            entry.status === 'VALID'         ? 'text-emerald-400 bg-emerald-400/10'  :
                            entry.status === 'EXPIRING_SOON' ? 'text-amber-400 bg-amber-400/10'      :
                            'text-red-400 bg-red-400/10'
                          )}>
                            {expiryStatusIcon(entry.status)}
                            {entry.status.replace('_', ' ')}
                          </span>
                        ) : (
                          <span className="text-gray-600">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="rounded-xl border border-dashed border-white/[0.06] py-6 text-center text-sm text-gray-500">
              No certificates currently loaded in gateway registry
            </div>
          )}
        </div>

        {/* ── Certificate File Sources ─────────────────────────────────────────── */}
        <div>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-white">Certificate File Sources</h3>
            <button
              onClick={() => setCfg(p => ({ ...p, fileSources: [...p.fileSources, { logicalId: '', certificatePath: '', watchForChanges: true }] }))}
              className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300"
            >
              <Plus className="w-3.5 h-3.5" /> Add Source
            </button>
          </div>
          <div className="space-y-3">
            {cfg.fileSources.map((src, i) => (
              <div key={i} className="bg-white/[0.03] rounded-xl border border-white/5 p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Server className="w-4 h-4 text-gray-400" />
                    <span className="text-sm text-white font-mono">{src.logicalId || '(new)'}</span>
                    {src.status && (
                      <span className={cn('text-xs font-medium', certStatusColor[src.status] ?? 'text-gray-400')}>
                        {src.status}
                      </span>
                    )}
                    {src.expiresAt && (
                      <span className="text-xs text-gray-500">
                        Expires: {new Date(src.expiresAt).toLocaleDateString()}
                      </span>
                    )}
                  </div>
                  <button
                    onClick={() => setCfg(p => ({ ...p, fileSources: p.fileSources.filter((_, j) => j !== i) }))}
                    className="p-1 text-red-400 hover:bg-red-400/10 rounded"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Logical ID">
                    <input
                      value={src.logicalId}
                      onChange={e => { const s = [...cfg.fileSources]; s[i] = { ...s[i], logicalId: e.target.value }; setCfg(p => ({ ...p, fileSources: s })) }}
                      className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500"
                    />
                  </Field>
                  <Field label="Certificate Path">
                    <input
                      value={src.certificatePath}
                      onChange={e => { const s = [...cfg.fileSources]; s[i] = { ...s[i], certificatePath: e.target.value }; setCfg(p => ({ ...p, fileSources: s })) }}
                      placeholder="/etc/certs/client.cer"
                      className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500"
                    />
                  </Field>
                </div>
                <ToggleRow
                  label="Watch for Changes"
                  description="Automatically hot-reload when file changes on disk"
                  checked={src.watchForChanges}
                  onChange={v => { const s = [...cfg.fileSources]; s[i] = { ...s[i], watchForChanges: v }; setCfg(p => ({ ...p, fileSources: s })) }}
                />
              </div>
            ))}
            {cfg.fileSources.length === 0 && (
              <div className="rounded-xl border border-dashed border-white/[0.06] py-6 text-center text-sm text-gray-500">
                No file sources configured — file-based certificates load from disk on startup
              </div>
            )}
          </div>
        </div>

        {/* ── Directory Sources ────────────────────────────────────────────────── */}
        <div>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-white">Directory Sources</h3>
            <button
              onClick={() => setCfg(p => ({ ...p, directorySources: [...(p.directorySources ?? []), { directoryPath: '', watchForChanges: true }] }))}
              className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300"
            >
              <Plus className="w-3.5 h-3.5" /> Add Directory
            </button>
          </div>
          <p className="text-xs text-gray-500 mb-3">
            Every <code className="bg-white/5 px-1 rounded">.pem</code>, <code className="bg-white/5 px-1 rounded">.cer</code>, and <code className="bg-white/5 px-1 rounded">.crt</code> file
            in these directories is automatically loaded. The filename stem is used as the logical ID.
          </p>
          <div className="space-y-3">
            {(cfg.directorySources ?? []).map((src, i) => (
              <div key={i} className="bg-white/[0.03] rounded-xl border border-white/5 p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <FolderOpen className="w-4 h-4 text-gray-400" />
                    <span className="text-sm text-white font-mono">{src.directoryPath || '(new)'}</span>
                  </div>
                  <button
                    onClick={() => setCfg(p => ({ ...p, directorySources: (p.directorySources ?? []).filter((_, j) => j !== i) }))}
                    className="p-1 text-red-400 hover:bg-red-400/10 rounded"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
                <Field label="Directory Path">
                  <input
                    value={src.directoryPath}
                    onChange={e => { const s = [...(cfg.directorySources ?? [])]; s[i] = { ...s[i], directoryPath: e.target.value }; setCfg(p => ({ ...p, directorySources: s })) }}
                    placeholder="/etc/gateway/certs/"
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500"
                  />
                </Field>
                <ToggleRow
                  label="Watch for Changes"
                  description="Automatically hot-reload when files in this directory change"
                  checked={src.watchForChanges}
                  onChange={v => { const s = [...(cfg.directorySources ?? [])]; s[i] = { ...s[i], watchForChanges: v }; setCfg(p => ({ ...p, directorySources: s })) }}
                />
              </div>
            ))}
            {(cfg.directorySources ?? []).length === 0 && (
              <div className="rounded-xl border border-dashed border-white/[0.06] py-6 text-center text-sm text-gray-500">
                No directory sources configured
              </div>
            )}
          </div>
        </div>

      </div>
      <SaveBar onSave={() => onSave(cfg)} isPending={isPending} dirty={dirty} />
    </div>
  )
}

// ─── NETWORKING TAB ───────────────────────────────────────────────────────────

function NetworkingTab({ initialProxy, initialHttp, onSaveProxy, onSaveHttp, isPending }: {
  initialProxy: GatewayProxyConfig
  initialHttp: GatewayHttpClientConfig
  onSaveProxy: (v: GatewayProxyConfig) => void
  onSaveHttp: (v: GatewayHttpClientConfig) => void
  isPending: boolean
}) {
  const [proxy, setProxy] = useState<GatewayProxyConfig>(initialProxy)
  const [http, setHttp] = useState<GatewayHttpClientConfig>(initialHttp)
  const dirtyProxy = JSON.stringify(proxy) !== JSON.stringify(initialProxy)
  const dirtyHttp = JSON.stringify(http) !== JSON.stringify(initialHttp)

  return (
    <div className="space-y-8">
      {/* Upstream Proxy */}
      <div>
        <SectionHeader title="Upstream Proxy" description="Configure an HTTP/HTTPS/SOCKS5 proxy for all upstream gateway requests" />
        <div className="max-w-2xl space-y-4">
          <ToggleRow label="Proxy Enabled" checked={proxy.enabled} onChange={v => setProxy(p => ({ ...p, enabled: v }))} />
          {proxy.enabled && (
            <>
              <div className="grid grid-cols-3 gap-4">
                <Field label="Type">
                  <Select
                    value={proxy.type}
                    onChange={v => setProxy(p => ({ ...p, type: v as GatewayProxyConfig['type'] }))}
                    options={[
                      { value: 'HTTP',   label: 'HTTP' },
                      { value: 'HTTPS',  label: 'HTTPS' },
                      { value: 'SOCKS5', label: 'SOCKS5' },
                    ]}
                  />
                </Field>
                <Field label="Host">
                  <input value={proxy.host ?? ''} onChange={e => setProxy(p => ({ ...p, host: e.target.value }))}
                    placeholder="proxy.company.com"
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
                </Field>
                <Field label="Port">
                  <input type="number" value={proxy.port ?? ''} onChange={e => setProxy(p => ({ ...p, port: Number(e.target.value) }))}
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                </Field>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Username">
                  <input value={proxy.username ?? ''} onChange={e => setProxy(p => ({ ...p, username: e.target.value }))}
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                </Field>
                <Field label="Password">
                  <input type="password" value={proxy.password ?? ''} onChange={e => setProxy(p => ({ ...p, password: e.target.value }))}
                    className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
                </Field>
              </div>
              <Field label="Non-Proxy Hosts" hint="One host pattern per line">
                <textarea rows={3} value={(proxy.nonProxyHosts ?? []).join('\n')}
                  onChange={e => setProxy(p => ({ ...p, nonProxyHosts: e.target.value.split('\n').map(s => s.trim()).filter(Boolean) }))}
                  className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500 resize-none" />
              </Field>
            </>
          )}
        </div>
        {dirtyProxy && (
          <div className="mt-4">
            <button onClick={() => onSaveProxy(proxy)} disabled={isPending}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm rounded-lg">
              <Save className="w-4 h-4" />
              {isPending ? 'Saving…' : 'Apply Proxy Settings'}
            </button>
          </div>
        )}
      </div>

      {/* HTTP Client */}
      <div>
        <SectionHeader title="HTTP Client (Reactor Netty)" description="Connection pool, timeout, and feature settings for upstream HTTP calls" />
        <div className="grid grid-cols-2 gap-4 max-w-2xl">
          {[
            { key: 'connectTimeoutMs',      label: 'Connect Timeout (ms)' },
            { key: 'responseTimeoutMs',     label: 'Response Timeout (ms)' },
            { key: 'maxConnections',        label: 'Max Connections' },
            { key: 'maxConnectionsPerRoute',label: 'Max Per Route' },
            { key: 'acquireTimeoutMs',      label: 'Acquire Timeout (ms)' },
          ].map(({ key, label }) => (
            <Field key={key} label={label}>
              <input type="number" value={(http as any)[key]} onChange={e => setHttp(p => ({ ...p, [key]: Number(e.target.value) }))}
                className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500" />
            </Field>
          ))}
          <Field label="Max Idle Time" hint="e.g. 20s">
            <input value={http.maxIdleTime} onChange={e => setHttp(p => ({ ...p, maxIdleTime: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
          </Field>
          <Field label="Max Life Time" hint="e.g. 60s">
            <input value={http.maxLifeTime} onChange={e => setHttp(p => ({ ...p, maxLifeTime: e.target.value }))}
              className="w-full bg-white/5 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500" />
          </Field>
        </div>
        <div className="mt-3 space-y-1 max-w-2xl">
          <ToggleRow label="GZip Compression" description="Compress upstream requests" checked={http.compressionEnabled} onChange={v => setHttp(p => ({ ...p, compressionEnabled: v }))} />
          <ToggleRow label="Follow Redirects" description="Automatically follow 3xx responses" checked={http.followRedirects} onChange={v => setHttp(p => ({ ...p, followRedirects: v }))} />
          <ToggleRow label="Wire Tap (debug)" description="⚠️ Logs all request/response bytes — production warning" checked={http.wiretapEnabled} onChange={v => setHttp(p => ({ ...p, wiretapEnabled: v }))} />
        </div>
        {dirtyHttp && (
          <div className="mt-4">
            <button onClick={() => onSaveHttp(http)} disabled={isPending}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm rounded-lg">
              <Save className="w-4 h-4" />
              {isPending ? 'Saving…' : 'Apply HTTP Client Settings'}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}


// ─── TENANT ISOLATION TAB ─────────────────────────────────────────────────────

const DEFAULT_TENANT_HEADER = 'X-Tenant-Id'

function TenantIsolationTab({ initial, onSave, isPending }: {
  initial: GatewayTenantIsolationConfig
  onSave: (v: GatewayTenantIsolationConfig) => void
  isPending: boolean
}) {
  const [cfg, setCfg] = useState<GatewayTenantIsolationConfig>(initial)
  const [confirmDisable, setConfirmDisable] = useState(false)
  const dirty = JSON.stringify(cfg) !== JSON.stringify(initial)

  const headerChanged = cfg.tenantIdHeader !== DEFAULT_TENANT_HEADER && cfg.tenantIdHeader !== initial.tenantIdHeader
  const isDisabling = !cfg.enabled && initial.enabled

  const handleToggleEnabled = (v: boolean) => {
    if (!v) {
      // Require explicit confirmation before disabling — this is a security-critical change.
      setConfirmDisable(true)
    } else {
      setCfg(p => ({ ...p, enabled: true }))
    }
  }

  return (
    <div>
      <SectionHeader
        title="Tenant Isolation"
        description="Multi-tenancy enforcement — ensures each tenant can only access their own routes via the X-Tenant-Id header predicate"
      />

      {/* Security notice */}
      <div className="max-w-2xl mb-6 bg-indigo-500/5 border border-indigo-500/20 rounded-xl p-4 text-sm text-gray-400 space-y-1">
        <div className="flex items-center gap-2 font-medium text-indigo-300 mb-1">
          <Shield className="w-4 h-4" />
          How tenant isolation works
        </div>
        <p className="text-xs leading-relaxed">
          Every route is compiled with a <code className="bg-white/10 px-1 rounded">Header=X-Tenant-Id,^&lt;uuid&gt;$</code> predicate.
          Only requests carrying the exact matching tenant UUID in <code className="bg-white/10 px-1 rounded">X-Tenant-Id</code> can match that route.
          The <span className="text-indigo-300">TenantContext</span> filter cross-validates this header against the JWT <code className="bg-white/10 px-1 rounded">tenantId</code> claim and rejects mismatches with <code className="bg-white/10 px-1 rounded">403 TENANT_MISMATCH</code>.
        </p>
        <p className="text-xs text-yellow-400/80 leading-relaxed mt-1">
          ⚠ These settings are stored in the database and broadcast to all gateway pods via Kafka. Disabling isolation or changing the header name
          will require all active routes to be reloaded and all API clients to be updated simultaneously.
        </p>
      </div>

      <div className="max-w-2xl space-y-1">

        {/* Master enable toggle with inline warning when off */}
        <div className="flex items-center justify-between py-3 border-b border-white/5">
          <div>
            <div className="text-sm text-white">Tenant Isolation Enabled</div>
            <div className="text-xs text-gray-500 mt-0.5">
              {cfg.enabled
                ? 'All routes enforce tenant-scoped access — recommended for production'
                : <span className="text-red-400 font-medium">⚠ Disabled — any client can reach any tenant\'s routes without a matching header</span>
              }
            </div>
          </div>
          <button
            type="button"
            onClick={() => handleToggleEnabled(!cfg.enabled)}
            className={cn(
              'relative shrink-0 w-9 h-5 rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0a0c10]',
              cfg.enabled ? 'bg-indigo-600 focus-visible:ring-indigo-500' : 'bg-red-700 focus-visible:ring-red-500',
            )}
          >
            <span className={cn(
              'absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform',
              cfg.enabled ? 'translate-x-4' : 'translate-x-0',
            )} />
          </button>
        </div>

        <ToggleRow
          label="Enforce Header Predicate on Route Compilation"
          description={`Route definitions will include a Header=${cfg.tenantIdHeader || DEFAULT_TENANT_HEADER},^<uuid>$ predicate — takes effect on next gateway reload`}
          checked={cfg.enforceHeaderPredicate}
          onChange={v => setCfg(p => ({ ...p, enforceHeaderPredicate: v }))}
        />

        <ToggleRow
          label="Allow Cross-Tenant Access for SUPER_ADMIN"
          description="Super admins bypass the tenant header predicate and can reach any tenant's routes — use only for support/debug purposes"
          checked={cfg.allowCrossTenantsForSuperAdmin}
          onChange={v => setCfg(p => ({ ...p, allowCrossTenantsForSuperAdmin: v }))}
        />

        <div className="pt-4">
          <Field
            label="Tenant ID Header Name"
            hint={`Default: ${DEFAULT_TENANT_HEADER}. Changing this requires updating all route predicates and every API client simultaneously — gateway reload required.`}
          >
            <div className="flex items-center gap-2">
              <input
                value={cfg.tenantIdHeader}
                onChange={e => setCfg(p => ({ ...p, tenantIdHeader: e.target.value }))}
                className={cn(
                  'w-64 bg-white/5 border rounded-lg px-3 py-2 text-sm text-white font-mono focus:outline-none',
                  headerChanged
                    ? 'border-yellow-500/60 focus:border-yellow-400'
                    : 'border-white/10 focus:border-indigo-500',
                )}
              />
              {cfg.tenantIdHeader !== DEFAULT_TENANT_HEADER && (
                <button
                  type="button"
                  onClick={() => setCfg(p => ({ ...p, tenantIdHeader: DEFAULT_TENANT_HEADER }))}
                  className="text-xs text-gray-400 hover:text-white underline"
                >
                  Reset to default
                </button>
              )}
            </div>
            {headerChanged && (
              <p className="text-xs text-yellow-400 mt-1">
                ⚠ Changing the header name will break all existing routes until they are reloaded and all clients are updated.
              </p>
            )}
          </Field>
        </div>

        {/* Non-obvious state warning: enabled=false + enforceHeaderPredicate=true */}
        {!cfg.enabled && cfg.enforceHeaderPredicate && (
          <div className="mt-3 bg-yellow-500/10 border border-yellow-500/20 rounded-lg px-4 py-3 text-xs text-yellow-300">
            Inconsistent state: isolation is disabled globally but header predicate enforcement is still on.
            Route compilation will still add the header predicate, but the TenantContext validation filter will not reject mismatches.
          </div>
        )}
      </div>

      {/* Disable confirmation dialog */}
      {confirmDisable && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-[#13151a] border border-red-500/30 rounded-xl w-full max-w-md shadow-2xl p-6 space-y-4">
            <div className="flex items-center gap-3">
              <AlertTriangle className="w-6 h-6 text-red-400 shrink-0" />
              <h3 className="text-base font-semibold text-white">Disable Tenant Isolation?</h3>
            </div>
            <p className="text-sm text-gray-400">
              Disabling tenant isolation means <strong className="text-white">any authenticated client can reach routes belonging to any tenant</strong> simply by omitting the <code className="bg-white/10 px-1 rounded">X-Tenant-Id</code> header.
            </p>
            <p className="text-sm text-red-400">
              This is a critical security change. Only disable this in controlled development environments.
            </p>
            <div className="flex justify-end gap-3 pt-2">
              <button
                onClick={() => setConfirmDisable(false)}
                className="px-4 py-2 text-sm text-gray-400 hover:text-white"
              >
                Cancel
              </button>
              <button
                onClick={() => { setCfg(p => ({ ...p, enabled: false })); setConfirmDisable(false) }}
                className="px-4 py-2 bg-red-600 hover:bg-red-500 text-white text-sm font-medium rounded-lg"
              >
                Disable Isolation
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Dirty warning when about to save a disabling change */}
      {isDisabling && dirty && (
        <div className="max-w-2xl mt-4 bg-red-500/10 border border-red-500/20 rounded-xl px-4 py-3 text-xs text-red-300">
          You are about to save a configuration that disables tenant isolation. This will take effect on all gateway pods after the next Kafka reload event.
        </div>
      )}

      <SaveBar onSave={() => onSave(cfg)} isPending={isPending} dirty={dirty} />
    </div>
  )
}

// ─── Main GatewayPage ─────────────────────────────────────────────────────────

export default function GatewayPage() {
  const qc = useQueryClient()
  const [activeTab, setActiveTab] = useState<GatewayTab>('overview')

  const { data: config, isLoading } = useQuery({
    queryKey: ['gateway-config'],
    queryFn: gatewayApi.getConfig,
  })

  // Section mutations
  const corsMutation         = useMutation({ mutationFn: gatewayApi.updateCors,              onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const secHeadersMutation   = useMutation({ mutationFn: gatewayApi.updateSecurityHeaders,    onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const rlMutation           = useMutation({ mutationFn: gatewayApi.setRateLimitPolicies,     onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const cbMutation           = useMutation({ mutationFn: gatewayApi.updateCircuitBreakerDefaults, onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const rdMutation           = useMutation({ mutationFn: gatewayApi.updateResilienceDefaults, onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const upsertAuthMutation   = useMutation({ mutationFn: gatewayApi.upsertAuthProvider,       onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const deleteAuthMutation   = useMutation({ mutationFn: gatewayApi.deleteAuthProvider,       onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const tlsMutation          = useMutation({ mutationFn: gatewayApi.updateTlsConfig,          onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const proxyMutation        = useMutation({ mutationFn: gatewayApi.updateProxyConfig,        onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const httpClientMutation   = useMutation({ mutationFn: gatewayApi.updateHttpClientConfig,   onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })
  const tenantMutation       = useMutation({ mutationFn: gatewayApi.updateTenantIsolation,    onSuccess: () => qc.invalidateQueries({ queryKey: ['gateway-config'] }) })

  if (isLoading || !config) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full">
      {/* Page Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-white/10">
        <div>
          <h1 className="text-xl font-semibold text-white">Gateway Configuration</h1>
          <p className="text-sm text-gray-400 mt-0.5">
            Full control over every gateway capability — changes persist to PostgreSQL and apply instantly via Kafka hot-reload
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 text-xs bg-green-500/10 border border-green-500/20 text-green-400 px-2.5 py-1 rounded-full">
            <Server className="w-3 h-3" />
            <span>DB-persisted</span>
          </div>
          <div className="flex items-center gap-2 text-xs">
            <Wifi className="w-4 h-4 text-green-400" />
            <span className="text-green-400">Live</span>
          </div>
        </div>
      </div>

      {/* Tab Bar */}
      <div className="flex items-center gap-0.5 px-4 border-b border-white/5 overflow-x-auto shrink-0">
        {TABS.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setActiveTab(id)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-3 text-xs font-medium whitespace-nowrap transition-colors border-b-2 -mb-px',
              activeTab === id
                ? 'text-indigo-300 border-indigo-500'
                : 'text-gray-500 border-transparent hover:text-gray-300',
            )}
          >
            <Icon className="w-3.5 h-3.5" />
            {label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      <div className="flex-1 overflow-auto px-6 py-6">
        {activeTab === 'overview' && <OverviewTab config={config} />}
        {activeTab === 'cors' && (
          <CorsTab
            key={config.updatedAt ?? 'cors'}
            initial={config.cors}
            onSave={(v) => corsMutation.mutate(v)}
            isPending={corsMutation.isPending}
          />
        )}
        {activeTab === 'security' && (
          <SecurityHeadersTab
            key={config.updatedAt ?? 'security'}
            initial={config.securityHeaders}
            onSave={(v) => secHeadersMutation.mutate(v)}
            isPending={secHeadersMutation.isPending}
          />
        )}
        {activeTab === 'rate-limit' && (
          <RateLimitTab
            key={config.updatedAt ?? 'rate-limit'}
            initial={config.rateLimitPolicies}
            onSave={(v) => rlMutation.mutate(v)}
            isPending={rlMutation.isPending}
          />
        )}
        {activeTab === 'resilience' && (
          <ResilienceTab
            key={config.updatedAt ?? 'resilience'}
            initialCb={config.circuitBreakerDefaults}
            initialRd={config.resilienceDefaults}
            onSaveCb={(v) => cbMutation.mutate(v)}
            onSaveRd={(v) => rdMutation.mutate(v)}
            isPendingCb={cbMutation.isPending}
            isPendingRd={rdMutation.isPending}
          />
        )}
        {activeTab === 'auth' && (
          <AuthProvidersTab
            key={config.updatedAt ?? 'auth'}
            initial={config.authProviders}
            onUpsert={(p) => upsertAuthMutation.mutate(p)}
            onDelete={(id) => deleteAuthMutation.mutate(id)}
            isPending={upsertAuthMutation.isPending || deleteAuthMutation.isPending}
          />
        )}
        {activeTab === 'tls' && (
          <TlsTab
            key={config.updatedAt ?? 'tls'}
            initial={config.tlsConfig}
            onSave={(v) => tlsMutation.mutate(v)}
            isPending={tlsMutation.isPending}
          />
        )}
        {activeTab === 'networking' && (
          <NetworkingTab
            key={config.updatedAt ?? 'networking'}
            initialProxy={config.proxyConfig}
            initialHttp={config.httpClientConfig}
            onSaveProxy={(v) => proxyMutation.mutate(v)}
            onSaveHttp={(v) => httpClientMutation.mutate(v)}
            isPending={proxyMutation.isPending || httpClientMutation.isPending}
          />
        )}
        {activeTab === 'tenant' && (
          <TenantIsolationTab
            key={config.updatedAt ?? 'tenant'}
            initial={config.tenantIsolation}
            onSave={(v) => tenantMutation.mutate(v)}
            isPending={tenantMutation.isPending}
          />
        )}
      </div>
    </div>
  )
}

