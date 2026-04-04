/**
 * TenantIsolationTab — Multi-tenancy enforcement configuration.
 * (Preserves the security confirmation modal; adds improved banner styling)
 */
import { useState } from 'react'
import { Shield, AlertTriangle, Lock } from 'lucide-react'
import type { GatewayTenantIsolationConfig } from '../../../types'
import {
  SectionHeader, ToggleRow, Field, SaveBar, InfoBanner,
} from '../components/GatewayPrimitives'
import { cn } from '../../../lib/utils'

const DEFAULT_TENANT_HEADER = 'X-Tenant-Id'

interface Props {
  initial: GatewayTenantIsolationConfig
  onSave: (v: GatewayTenantIsolationConfig) => void
  isPending: boolean
}

export default function TenantIsolationTab({ initial, onSave, isPending }: Props) {
  const [cfg, setCfg] = useState<GatewayTenantIsolationConfig>(initial)
  const [confirmDisable, setConfirmDisable] = useState(false)
  const dirty         = JSON.stringify(cfg) !== JSON.stringify(initial)
  const isDisabling   = !cfg.enabled && initial.enabled

  const handleToggleEnabled = (v: boolean) => {
    if (!v) setConfirmDisable(true)
    else setCfg(p => ({ ...p, enabled: true }))
  }

  return (
    <div className="max-w-2xl">
      <SectionHeader
        icon={Shield}
        title="Tenant Isolation"
        description="Multi-tenancy enforcement — controls how tenant context is resolved and propagated through the gateway filter chain and to upstream destinations."
      />

      <InfoBanner variant="info">
        <div className="font-semibold text-indigo-300 mb-1.5">How tenant isolation works</div>
        <p className="leading-relaxed">
          <strong className="text-indigo-300">Caller Provides Tenant Header</strong> controls <em>who provides</em> the tenant header.
          When on, callers must supply{' '}
          <code className="bg-white/10 px-1 rounded">X-Tenant-Id</code> and each route includes a{' '}
          <code className="bg-white/10 px-1 rounded">Header=X-Tenant-Id,^&lt;uuid&gt;$</code> predicate.
          When off, the gateway auto-injects the tenant from route metadata — callers do not need to supply it.
        </p>
        <p className="leading-relaxed mt-2">
          In both modes the <code className="bg-white/10 px-1 rounded">X-Tenant-Id</code> header is
          always forwarded to the upstream destination.
        </p>
        <p className="text-amber-400/80 mt-2 leading-relaxed">
          ⚠ When <strong>Caller Provides Tenant Header</strong> is disabled, routes <strong>must</strong> include the{' '}
          <code className="bg-white/10 px-1 rounded">TENANT_CONTEXT</code> filter so that the gateway
          auto-injects the tenant header for downstream filters (rate limiters, request logger, etc.)
          to work properly.
        </p>
        <p className="text-amber-400/80 mt-1.5 leading-relaxed">
          ⚠ These settings are stored in the database and broadcast to all gateway pods via Kafka.
          Changing them requires all active routes to be reloaded.
        </p>
      </InfoBanner>

      <div className="mt-6 space-y-1">
        {/* Master toggle with a danger-ring variant when off */}
        <div className={cn(
          'flex items-center justify-between py-3.5 px-4 rounded-xl border transition-colors',
          cfg.enabled
            ? 'bg-emerald-500/5 border-emerald-500/20'
            : 'bg-amber-500/[0.07] border-amber-500/30',
        )}>
          <div className="mr-4">
            <div className="text-sm font-semibold text-white">Caller Provides Tenant Header</div>
            <div className="text-xs mt-0.5">
              {cfg.enabled
                ? <span className="text-emerald-400">Callers must supply X-Tenant-Id — routes include a header predicate for tenant-scoped matching</span>
                : <span className="text-amber-400 font-medium">Auto-inject mode — the gateway resolves the tenant from route metadata; callers do not need to supply X-Tenant-Id. Routes must include the TENANT_CONTEXT filter.</span>
              }
            </div>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={cfg.enabled}
            onClick={() => handleToggleEnabled(!cfg.enabled)}
            className={cn(
              'relative shrink-0 w-9 h-5 rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0a0c10]',
              cfg.enabled
                ? 'bg-indigo-600 focus-visible:ring-indigo-500'
                : 'bg-amber-600 focus-visible:ring-amber-500',
            )}
          >
            <span className={cn(
              'absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform duration-150',
              cfg.enabled ? 'translate-x-4' : 'translate-x-0',
            )} />
          </button>
        </div>

        <ToggleRow
          label="Allow Cross-Tenant Access for SUPER_ADMIN"
          description="Super admins bypass the tenant header predicate and can reach any tenant's routes — use only for support/debug purposes"
          checked={cfg.allowCrossTenantsForSuperAdmin}
          onChange={v => setCfg(p => ({ ...p, allowCrossTenantsForSuperAdmin: v }))}
        />

        <div className="pt-4">
          <Field
            label="Tenant ID Header Name"
            hint="This is a platform-wide constant defined in RoutifyHeaders. All services, route predicates, and API clients depend on this value — it cannot be changed at runtime."
          >
            <div className="flex items-center gap-2">
              <div className="flex items-center gap-2 w-64 bg-white/[0.02] border border-white/[0.06] rounded-lg px-3 py-2 text-sm text-gray-400 font-mono select-all cursor-default">
                <Lock className="w-3.5 h-3.5 text-gray-600 shrink-0" />
                <span>{DEFAULT_TENANT_HEADER}</span>
              </div>
              <span className="text-[10px] text-gray-600 bg-white/[0.03] border border-white/[0.05] px-2 py-0.5 rounded-full">read-only</span>
            </div>
          </Field>
        </div>
      </div>

      {isDisabling && dirty && (
        <div className="mt-4">
          <InfoBanner variant="warning">
            You are switching to <strong>auto-inject mode</strong>. The gateway will resolve tenant
            context from route metadata instead of requiring callers to supply X-Tenant-Id. Routes
            will no longer include the tenant header predicate after the next Kafka reload event.
            Make sure all affected routes include the <strong>TENANT_CONTEXT</strong> filter.
          </InfoBanner>
        </div>
      )}

      {/* Disable confirmation dialog */}
      {confirmDisable && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-[#13151a] border border-amber-500/30 rounded-xl w-full max-w-md shadow-2xl p-6 space-y-4">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-amber-500/10 border border-amber-500/20 flex items-center justify-center shrink-0">
                <AlertTriangle className="w-5 h-5 text-amber-400" />
              </div>
              <h3 className="text-base font-semibold text-white">Switch to Auto-Inject Mode?</h3>
            </div>
            <p className="text-sm text-gray-400 leading-relaxed">
              When disabled, callers no longer need to supply <code className="bg-white/10 px-1 rounded">X-Tenant-Id</code>.
              The gateway will auto-inject the tenant from route metadata. Routes will <strong className="text-white">not</strong> include
              a tenant header predicate, so the header is no longer required to match a route.
            </p>
            <p className="text-sm text-amber-400 leading-relaxed">
              Routes <strong>must</strong> include the <code className="bg-white/10 px-1 rounded">TENANT_CONTEXT</code> filter
              so that tenant context is available to downstream filters (rate limiters, request logger, audit, etc.).
            </p>
            <div className="flex justify-end gap-3 pt-2">
              <button
                onClick={() => setConfirmDisable(false)}
                className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => { setCfg(p => ({ ...p, enabled: false })); setConfirmDisable(false) }}
                className="px-4 py-2 bg-amber-600 hover:bg-amber-500 text-white text-sm font-medium rounded-lg transition-colors"
              >
                Switch to Auto-Inject
              </button>
            </div>
          </div>
        </div>
      )}

      <SaveBar onSave={() => onSave({ ...cfg, tenantIdHeader: DEFAULT_TENANT_HEADER })} isPending={isPending} dirty={dirty} />
    </div>
  )
}
