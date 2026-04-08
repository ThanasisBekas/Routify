/**
 * InstanceCard — Displays a single gateway instance's status in the fleet view.
 *
 * Shows hostname, config version (green badge if matches global, amber if behind),
 * route count, uptime, and last reload time. Stale instances get an amber border
 * and a "Config version behind" warning.
 */
import { Server, AlertTriangle, CheckCircle, Clock, Layers, RefreshCw } from 'lucide-react'
import { cn } from '../../../lib/utils'
import type { GatewayInstanceInfo, GatewayInstanceStatus } from '../../../types'

interface InstanceCardProps {
  instance: GatewayInstanceInfo
  globalConfigVersion: number
}

const STATUS_CONFIG: Record<
  GatewayInstanceStatus,
  { color: string; borderColor: string; icon: typeof CheckCircle; label: string }
> = {
  HEALTHY: {
    color: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
    borderColor: 'border-emerald-500/20',
    icon: CheckCircle,
    label: 'Healthy',
  },
  STALE: {
    color: 'text-amber-400 bg-amber-400/10 border-amber-400/20',
    borderColor: 'border-amber-500/30',
    icon: AlertTriangle,
    label: 'Stale',
  },
  UNRESPONSIVE: {
    color: 'text-red-400 bg-red-400/10 border-red-400/20',
    borderColor: 'border-red-500/30',
    icon: AlertTriangle,
    label: 'Unresponsive',
  },
}

function formatUptime(hours: number): string {
  if (hours < 1) return `${Math.round(hours * 60)}m`
  if (hours < 24) return `${hours.toFixed(1)}h`
  const days = Math.floor(hours / 24)
  const remainingHours = hours % 24
  return `${days}d ${remainingHours.toFixed(0)}h`
}

function formatTime(isoString: string): string {
  if (!isoString) return '—'
  try {
    const date = new Date(isoString)
    return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch {
    return '—'
  }
}

export default function InstanceCard({ instance, globalConfigVersion }: InstanceCardProps) {
  const statusCfg = STATUS_CONFIG[instance.status] ?? STATUS_CONFIG.UNRESPONSIVE
  const StatusIcon = statusCfg.icon
  const isVersionBehind = instance.configVersion < globalConfigVersion

  return (
    <div
      className={cn(
        'bg-white/[0.03] rounded-xl border p-5 transition-all',
        instance.status === 'STALE'
          ? 'border-amber-500/30 bg-amber-500/[0.02]'
          : instance.status === 'UNRESPONSIVE'
            ? 'border-red-500/30 bg-red-500/[0.02]'
            : 'border-white/[0.06]',
      )}
    >
      {/* Header: hostname + status badge */}
      <div className="flex items-start justify-between gap-3 mb-4">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="shrink-0 w-8 h-8 rounded-lg bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center">
            <Server className="w-4 h-4 text-indigo-400" />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-semibold text-white truncate">{instance.hostname}</p>
            <p className="text-[10px] text-gray-600 font-mono truncate">{instance.instanceId}</p>
          </div>
        </div>
        <span
          className={cn(
            'inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full font-medium border shrink-0',
            statusCfg.color,
          )}
        >
          <StatusIcon className="w-3 h-3" />
          {statusCfg.label}
        </span>
      </div>

      {/* Config version badge */}
      <div className="flex items-center gap-2 mb-3">
        <span className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">Config Version</span>
        <span
          className={cn(
            'text-xs font-mono font-bold px-2 py-0.5 rounded-md border',
            isVersionBehind
              ? 'text-amber-400 bg-amber-400/10 border-amber-400/20'
              : 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
          )}
        >
          v{instance.configVersion}
          {isVersionBehind && <span className="ml-1 text-[10px] text-amber-500">(global: v{globalConfigVersion})</span>}
        </span>
      </div>

      {/* Stale warning */}
      {instance.status === 'STALE' && (
        <div className="mb-3 rounded-lg border border-amber-500/20 bg-amber-500/[0.05] px-3 py-2 flex items-center gap-2">
          <AlertTriangle className="w-3.5 h-3.5 text-amber-400 shrink-0" />
          <span className="text-xs text-amber-300/90">
            Config version behind — this instance may serve stale routes
          </span>
        </div>
      )}

      {/* Stats grid */}
      <div className="grid grid-cols-2 gap-3 mt-3">
        <div className="flex items-center gap-2">
          <Layers className="w-3.5 h-3.5 text-gray-500" />
          <div>
            <div className="text-[10px] text-gray-600">Routes</div>
            <div className="text-sm font-semibold text-white">{instance.routeCount}</div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Clock className="w-3.5 h-3.5 text-gray-500" />
          <div>
            <div className="text-[10px] text-gray-600">Uptime</div>
            <div className="text-sm font-semibold text-white">{formatUptime(instance.uptimeHours)}</div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <RefreshCw className="w-3.5 h-3.5 text-gray-500" />
          <div>
            <div className="text-[10px] text-gray-600">Last Reload</div>
            <div className="text-sm font-semibold text-white">{formatTime(instance.lastReloadAt)}</div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Server className="w-3.5 h-3.5 text-gray-500" />
          <div>
            <div className="text-[10px] text-gray-600">Last Heartbeat</div>
            <div className="text-sm font-semibold text-white">{formatTime(instance.lastHeartbeatAt)}</div>
          </div>
        </div>
      </div>
    </div>
  )
}
