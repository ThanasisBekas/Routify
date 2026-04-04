/**
 * OverviewTab — Live gateway health dashboard.
 *
 * Redesigned as a 4-column KPI bar + circuit-breaker cards grid +
 * config summary chips + 3-step persistence flow infographic.
 */
import { useQueryClient, useMutation } from '@tanstack/react-query'
import {
  Activity, Route, RefreshCw, RotateCcw, Server, Wifi,
  CheckCircle, AlertTriangle, Gauge, Shield, ShieldCheck,
} from 'lucide-react'
import { gatewayApi } from '../../../api/gatewayApi'
import { routesApi } from '../../../api/routesApi'
import { useWsStore } from '../../../store/wsStore'
import { useRealtimeQuery } from '../../../hooks/useRealtimeQuery'
import { cn } from '../../../lib/utils'
import type { GatewayConfig } from '../../../types'
import { StatTile, StatusBadge, Card, InfoBanner } from '../components/GatewayPrimitives'

// ─── Circuit-breaker state card ───────────────────────────────────────────────

function CbCard({ name, state }: {
  name: string
  state: { state: string; failureRate: number; bufferedCalls: number }
}) {
  const norm = (state.state ?? 'UNKNOWN').toUpperCase()
  const ring = {
    CLOSED:    'border-emerald-500/30 bg-emerald-500/5',
    OPEN:      'border-red-500/40 bg-red-500/[0.07]',
    HALF_OPEN: 'border-amber-500/30 bg-amber-500/[0.06]',
  }[norm] ?? 'border-white/[0.06] bg-white/[0.02]'

  return (
    <div className={cn('rounded-xl border p-4 space-y-3', ring)}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-sm font-medium text-white font-mono truncate">{name}</div>
        </div>
        <StatusBadge state={state.state} />
      </div>
      <div className="grid grid-cols-2 gap-2 text-xs">
        <div className="bg-white/[0.04] rounded-lg px-3 py-2">
          <div className="text-gray-500 mb-0.5">Failure rate</div>
          <div className={cn(
            'font-semibold',
            state.failureRate > 50 ? 'text-red-400' :
            state.failureRate > 20 ? 'text-amber-400' : 'text-emerald-400',
          )}>
            {state.failureRate?.toFixed(1)}%
          </div>
        </div>
        <div className="bg-white/[0.04] rounded-lg px-3 py-2">
          <div className="text-gray-500 mb-0.5">Buffered calls</div>
          <div className="font-semibold text-white">{state.bufferedCalls}</div>
        </div>
      </div>
    </div>
  )
}

// ─── Config summary chip grid ─────────────────────────────────────────────────

function ConfigChip({ label, ok, value }: { label: string; ok: boolean; value: string }) {
  return (
    <div className="bg-white/[0.025] rounded-lg px-4 py-3 border border-white/[0.05]">
      <div className="text-[10px] text-gray-500 uppercase tracking-wider mb-1 font-medium">{label}</div>
      <div className={cn('text-xs font-semibold flex items-center gap-1.5', ok ? 'text-emerald-400' : 'text-amber-400')}>
        {ok
          ? <CheckCircle className="w-3 h-3 shrink-0" />
          : <AlertTriangle className="w-3 h-3 shrink-0" />
        }
        {value}
      </div>
    </div>
  )
}

// ─── Main component ───────────────────────────────────────────────────────────

interface Props {
  config: GatewayConfig
}

export default function OverviewTab({ config }: Props) {
  const qc = useQueryClient()
  const wsCbStates     = useWsStore(s => s.circuitBreakers)
  const wsHealth       = useWsStore(s => s.gatewayHealth)
  const wsLoadedRoutes = useWsStore(s => s.wsLoadedRoutes)
  const wsStatus       = useWsStore(s => s.status)

  const { data: status } = useRealtimeQuery({
    queryKey: ['gateway-status'],
    queryFn: gatewayApi.getStatus,
    wsEvents: ['gateway', 'route'],
  })

  const { data: activeRoutesPage } = useRealtimeQuery({
    queryKey: ['active-routes-count'],
    queryFn: () => routesApi.list({ status: 'ACTIVE', page: 0, size: 1 }),
    staleTime: 10_000,
    wsEvents: ['route'],
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
  const loadedRoutes = wsLoadedRoutes ?? status?.routes?.count ?? activeRoutesPage?.totalElements
  const cbCount = Object.keys(cbStates).length
  const openCbs = Object.values(cbStates).filter(s => s.state?.toUpperCase() === 'OPEN').length

  // Reload is exposed at page level; this is a read-only overview

  return (
    <div className="space-y-7 max-w-5xl">

      {/* Live indicator header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-white">Gateway Overview</h2>
          <p className="text-sm text-gray-400 mt-0.5">Live status and circuit breaker health</p>
        </div>
        <div className="flex items-center gap-3">
          {wsStatus === 'CONNECTED' && (
            <div className="flex items-center gap-1.5 text-xs text-emerald-400 bg-emerald-400/10 border border-emerald-400/20 px-2.5 py-1 rounded-full">
              <Wifi className="w-3 h-3 animate-pulse" />
              Metrics live
            </div>
          )}
          <button
            onClick={() => reloadMutation.mutate()}
            disabled={reloadMutation.isPending}
            className="flex items-center gap-2 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-xs font-medium rounded-lg transition-colors"
          >
            <RotateCcw className={cn('w-3.5 h-3.5', reloadMutation.isPending && 'animate-spin')} />
            {reloadMutation.isPending ? 'Reloading…' : 'Force Reload'}
          </button>
        </div>
      </div>

      {/* KPI row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatTile
          icon={Activity}
          label="Gateway Health"
          color={health?.status?.toUpperCase() === 'UP' ? 'emerald' : 'rose'}
          value={<StatusBadge state={health?.status ?? 'UNKNOWN'} />}
          sub={health?.components ? `${Object.keys(health.components).length} components` : undefined}
        />
        <StatTile
          icon={Route}
          label="Loaded Routes"
          color="indigo"
          value={loadedRoutes ?? '—'}
          sub="active in gateway"
        />
        <StatTile
          icon={RefreshCw}
          label="Circuit Breakers"
          color={openCbs > 0 ? 'rose' : 'emerald'}
          value={cbCount}
          sub={openCbs > 0 ? `${openCbs} open` : 'all healthy'}
        />
        <StatTile
          icon={Gauge}
          label="Rate Policies"
          color="amber"
          value={config.rateLimitPolicies.filter(p => p.enabled).length}
          sub={`of ${config.rateLimitPolicies.length} active`}
        />
      </div>

      {/* Config summary chips */}
      <div>
        <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-3">Configuration Status</div>
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
          <ConfigChip label="CORS" ok={config.cors.enabled} value={config.cors.enabled ? 'Enabled' : 'Disabled'} />
          <ConfigChip label="Security Headers" ok={config.securityHeaders.enabled} value={config.securityHeaders.enabled ? 'Enabled' : 'Disabled'} />
          <ConfigChip label="Auth Providers" ok={config.authProviders.some(p => p.enabled)} value={`${config.authProviders.filter(p => p.enabled).length} enabled`} />
          <ConfigChip label="Tenant Isolation" ok={config.tenantIsolation.enabled} value={config.tenantIsolation.enabled ? 'Caller-provided' : 'Auto-injected'} />
          <ConfigChip label="Rate Limiting" ok={config.rateLimitPolicies.some(p => p.enabled)} value={`${config.rateLimitPolicies.filter(p => p.enabled).length} policies`} />
        </div>
      </div>

      {/* Circuit Breaker States */}
      <div>
        <div className="flex items-center justify-between mb-3">
          <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest">Circuit Breakers</div>
          {wsStatus === 'CONNECTED' && (
            <span className="text-[10px] text-emerald-500 flex items-center gap-1">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
              live
            </span>
          )}
        </div>
        {cbCount > 0 ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
            {Object.entries(cbStates).map(([name, state]) => (
              <CbCard key={name} name={name} state={state} />
            ))}
          </div>
        ) : (
          <Card>
            <div className="flex items-center gap-3 text-sm text-gray-500">
              <RefreshCw className="w-4 h-4 text-gray-600" />
              No circuit breaker data — data streams in once gateway routes are active
            </div>
          </Card>
        )}
      </div>

      {/* Health components breakdown */}
      {health?.components && Object.keys(health.components).length > 0 && (
        <div>
          <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-3">Health Components</div>
          <Card padded={false}>
            <div className="divide-y divide-white/[0.05]">
              {Object.entries(health.components as Record<string, { status: string }>).map(([key, comp]) => (
                <div key={key} className="flex items-center justify-between px-5 py-3">
                  <span className="text-sm text-gray-300 capitalize">{key}</span>
                  <StatusBadge state={comp.status} />
                </div>
              ))}
            </div>
          </Card>
        </div>
      )}

      {/* Persistence flow */}
      <InfoBanner variant="info">
        <div className="flex items-center gap-2 font-semibold text-indigo-300 mb-3">
          <Server className="w-4 h-4" />
          Rollout-Safe Configuration Persistence
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          {[
            { step: '1', icon: Shield,      label: 'Admin saves config',    detail: 'Written to PostgreSQL via route-service — durable source of truth, survives pod restarts' },
            { step: '2', icon: Activity,    label: 'Kafka event published', detail: 'GatewayConfigChanged → routify.gateway.config topic via transactional outbox (at-least-once)' },
            { step: '3', icon: ShieldCheck, label: 'All pods reload',       detail: 'Every gateway instance reloads config from DB on event receipt — Redis-independent' },
          ].map(item => (
            <div key={item.step} className="bg-white/[0.04] rounded-lg p-3 flex gap-3">
              <div className="shrink-0 w-7 h-7 rounded-full bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center text-indigo-300 text-xs font-bold">
                {item.step}
              </div>
              <div>
                <div className="text-white text-xs font-semibold mb-0.5">{item.label}</div>
                <div className="text-gray-500 text-[11px] leading-relaxed">{item.detail}</div>
              </div>
            </div>
          ))}
        </div>
      </InfoBanner>

      {config.updatedAt && (
        <p className="text-xs text-gray-600">
          Last config change: {new Date(config.updatedAt).toLocaleString()} by {config.updatedBy ?? 'unknown'}
        </p>
      )}
    </div>
  )
}

