/**
 * GatewayPage — API Gateway configuration hub.
 *
 * Pure orchestration shell: loads config once, owns all 11 mutations,
 * and delegates every tab's UI to its own component under ./tabs/.
 *
 * Tabs:
 *  overview    — live health, KPI stats, circuit-breaker cards, persistence flow
 *  cors        — origin policies, methods, headers, credentials
 *  security    — OWASP response headers grouped by threat category
 *  rate-limit  — token-bucket / fixed-window / sliding-window policies
 *  resilience  — circuit breaker + retry/timeout/bulkhead (sub-tabbed)
 *  auth        — OAuth2 CC, password, introspect, JWT JWKS, Basic providers
 *  networking  — upstream proxy, Reactor Netty HTTP client pool
 *  tenant      — multi-tenancy isolation enforcement
 *  global-filters — cross-cutting filters applied to all routes
 *
 * URL deep-linking: ?tab=cors — persists active tab in the query string
 * so users can bookmark / share specific sections.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Activity, Globe, Shield, Gauge, RefreshCw, Lock,
  Network, Settings, Wifi, Database, Layers,
} from 'lucide-react'
import { gatewayApi } from '../../api/gatewayApi'
import { useWsStore } from '../../store/wsStore'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import { cn } from '../../lib/utils'

// ─── Tab components ───────────────────────────────────────────────────────────
import OverviewTab        from './tabs/OverviewTab'
import CorsTab            from './tabs/CorsTab'
import SecurityHeadersTab from './tabs/SecurityHeadersTab'
import RateLimitTab       from './tabs/RateLimitTab'
import ResilienceTab      from './tabs/ResilienceTab'
import AuthProvidersTab   from './tabs/AuthProvidersTab'
import NetworkingTab      from './tabs/NetworkingTab'
import TenantIsolationTab from './tabs/TenantIsolationTab'
import GlobalFiltersTab   from './tabs/GlobalFiltersTab'

// ─── Tab catalogue ────────────────────────────────────────────────────────────

type GatewayTab =
  | 'overview' | 'cors' | 'security' | 'rate-limit' | 'resilience'
  | 'auth' | 'networking' | 'tenant' | 'global-filters'

const TABS: {
  id: GatewayTab
  label: string
  icon: React.ComponentType<{ className?: string }>
  description: string
}[] = [
  { id: 'overview',   label: 'Overview',         icon: Activity,  description: 'Live health and KPI stats' },
  { id: 'cors',       label: 'CORS',             icon: Globe,     description: 'Cross-origin request policies' },
  { id: 'security',   label: 'Security Headers', icon: Shield,    description: 'OWASP response headers' },
  { id: 'rate-limit', label: 'Rate Limiting',    icon: Gauge,     description: 'Global token-bucket policies' },
  { id: 'resilience', label: 'Resilience',       icon: RefreshCw, description: 'Circuit breaker, retry, timeout' },
  { id: 'auth',       label: 'Auth Providers',   icon: Lock,      description: 'JWT, OAuth2, Basic providers' },
  { id: 'networking', label: 'Networking',       icon: Network,   description: 'Proxy and HTTP client pool' },
  { id: 'tenant',     label: 'Tenant Isolation', icon: Settings,  description: 'Multi-tenancy enforcement' },
  { id: 'global-filters', label: 'Global Filters', icon: Layers, description: 'Cross-cutting filters applied to all routes' },
]

// ─── Main page ────────────────────────────────────────────────────────────────

export default function GatewayPage() {
  const qc = useQueryClient()
  const wsStatus = useWsStore(s => s.status)

  // ── URL-hash tab state ────────────────────────────────────────────────────
  const [params, setParams] = useSearchParams()
  const rawTab = params.get('tab') as GatewayTab | null
  const activeTab: GatewayTab = TABS.some(t => t.id === rawTab) ? rawTab! : 'overview'
  const setTab = (id: GatewayTab) => setParams({ tab: id }, { replace: true })

  // ── Config query ──────────────────────────────────────────────────────────
  const { data: config, isLoading } = useRealtimeQuery({
    queryKey: ['gateway-config'],
    queryFn: gatewayApi.getConfig,
    wsEvents: ['gateway'],
  })

  // ── Mutations (all invalidate the config cache on success) ────────────────
  const invalidate = () => qc.invalidateQueries({ queryKey: ['gateway-config'] })

  const corsMutation         = useMutation({ mutationFn: gatewayApi.updateCors,              onSuccess: invalidate })
  const secHeadersMutation   = useMutation({ mutationFn: gatewayApi.updateSecurityHeaders,    onSuccess: invalidate })
  const rlMutation           = useMutation({ mutationFn: gatewayApi.setRateLimitPolicies,     onSuccess: invalidate })
  const cbMutation           = useMutation({ mutationFn: gatewayApi.updateCircuitBreakerDefaults, onSuccess: invalidate })
  const rdMutation           = useMutation({ mutationFn: gatewayApi.updateResilienceDefaults, onSuccess: invalidate })
  const upsertAuthMutation   = useMutation({ mutationFn: gatewayApi.upsertAuthProvider,       onSuccess: invalidate })
  const deleteAuthMutation   = useMutation({ mutationFn: gatewayApi.deleteAuthProvider,       onSuccess: invalidate })
  const proxyMutation        = useMutation({ mutationFn: gatewayApi.updateProxyConfig,        onSuccess: invalidate })
  const httpClientMutation   = useMutation({ mutationFn: gatewayApi.updateHttpClientConfig,   onSuccess: invalidate })
  const tenantMutation       = useMutation({ mutationFn: gatewayApi.updateTenantIsolation,    onSuccess: invalidate })
  const globalFiltersMutation = useMutation({ mutationFn: gatewayApi.updateGlobalFilterEntries, onSuccess: invalidate })

  // ── Loading skeleton ──────────────────────────────────────────────────────
  if (isLoading || !config) {
    return (
      <div className="flex items-center justify-center py-24">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
          <p className="text-sm text-gray-500">Loading gateway configuration…</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full">

      {/* ── Page Header ──────────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.08] shrink-0">
        <div>
          <h1 className="text-xl font-bold text-white">Gateway Configuration</h1>
          <p className="text-sm text-gray-400 mt-0.5">
            Full control over routing, security, resilience, and networking — changes persist to PostgreSQL and reload via Kafka
          </p>
        </div>
        <div className="flex items-center gap-2">
          {/* DB-persisted badge */}
          <div className="hidden sm:flex items-center gap-1.5 text-xs bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 px-2.5 py-1 rounded-full">
            <Database className="w-3 h-3" />
            <span>DB-persisted</span>
          </div>
          {/* Live WS indicator */}
          <div className={cn(
            'flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full border',
            wsStatus === 'CONNECTED'
              ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-400'
              : 'bg-gray-500/10 border-gray-500/20 text-gray-500',
          )}>
            <Wifi className={cn('w-3 h-3', wsStatus === 'CONNECTED' && 'animate-pulse')} />
            <span>{wsStatus === 'CONNECTED' ? 'Live' : 'Offline'}</span>
          </div>
        </div>
      </div>

      {/* ── Tab Bar ──────────────────────────────────────────────────────────── */}
      <nav
        aria-label="Gateway configuration tabs"
        className="flex items-end gap-0 px-2 border-b border-white/[0.05] overflow-x-auto shrink-0 scrollbar-none"
      >
        {TABS.map(({ id, label, icon: Icon, description }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            title={description}
            className={cn(
              'group flex items-center gap-1.5 px-3.5 py-3 text-xs font-medium whitespace-nowrap transition-all border-b-2 -mb-px focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-500',
              activeTab === id
                ? 'text-indigo-300 border-indigo-500 bg-indigo-500/[0.04]'
                : 'text-gray-500 border-transparent hover:text-gray-300 hover:bg-white/[0.02]',
            )}
          >
            <Icon className={cn(
              'w-3.5 h-3.5 transition-colors',
              activeTab === id ? 'text-indigo-400' : 'text-gray-600 group-hover:text-gray-400',
            )} />
            {label}
          </button>
        ))}
      </nav>

      {/* ── Tab Content ──────────────────────────────────────────────────────── */}
      <div className="flex-1 overflow-auto px-6 py-6">

        {activeTab === 'overview' && (
          <OverviewTab config={config} />
        )}

        {activeTab === 'cors' && (
          <CorsTab
            key={config.updatedAt ?? 'cors'}
            initial={config.cors}
            onSave={v => corsMutation.mutate(v)}
            isPending={corsMutation.isPending}
          />
        )}

        {activeTab === 'security' && (
          <SecurityHeadersTab
            key={config.updatedAt ?? 'security'}
            initial={config.securityHeaders}
            onSave={v => secHeadersMutation.mutate(v)}
            isPending={secHeadersMutation.isPending}
          />
        )}

        {activeTab === 'rate-limit' && (
          <RateLimitTab
            key={config.updatedAt ?? 'rate-limit'}
            initial={config.rateLimitPolicies}
            onSave={v => rlMutation.mutate(v)}
            isPending={rlMutation.isPending}
          />
        )}

        {activeTab === 'resilience' && (
          <ResilienceTab
            key={config.updatedAt ?? 'resilience'
            }
            initialCb={config.circuitBreakerDefaults}
            initialRd={config.resilienceDefaults}
            onSaveCb={v => cbMutation.mutate(v)}
            onSaveRd={v => rdMutation.mutate(v)}
            isPendingCb={cbMutation.isPending}
            isPendingRd={rdMutation.isPending}
          />
        )}

        {activeTab === 'auth' && (
          <AuthProvidersTab
            key={config.updatedAt ?? 'auth'}
            initial={config.authProviders}
            onUpsert={p => upsertAuthMutation.mutate(p)}
            onDelete={id => deleteAuthMutation.mutate(id)}
            isPending={upsertAuthMutation.isPending || deleteAuthMutation.isPending}
          />
        )}


        {activeTab === 'networking' && (
          <NetworkingTab
            key={config.updatedAt ?? 'networking'}
            initialProxy={config.proxyConfig}
            initialHttp={config.httpClientConfig}
            onSaveProxy={v => proxyMutation.mutate(v)}
            onSaveHttp={v => httpClientMutation.mutate(v)}
            isPending={proxyMutation.isPending || httpClientMutation.isPending}
          />
        )}

        {activeTab === 'tenant' && (
          <TenantIsolationTab
            key={config.updatedAt ?? 'tenant'}
            initial={config.tenantIsolation}
            onSave={v => tenantMutation.mutate(v)}
            isPending={tenantMutation.isPending}
          />
        )}

        {activeTab === 'global-filters' && (
          <GlobalFiltersTab
            key={config.updatedAt ?? 'global-filters'}
            initial={config.globalFilterEntries ?? []}
            onSave={v => globalFiltersMutation.mutate(v)}
            isPending={globalFiltersMutation.isPending}
          />
        )}

      </div>
    </div>
  )
}

