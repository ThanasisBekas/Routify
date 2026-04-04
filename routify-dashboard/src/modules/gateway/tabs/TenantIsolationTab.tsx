/**
 * TenantIsolationTab — Multi-tenancy enforcement configuration.
 * (Preserves the security confirmation modal; adds improved banner styling)
 */
import { useState } from 'react'
import { Shield, AlertTriangle } from 'lucide-react'
import type { GatewayTenantIsolationConfig } from '../../../types'
import {
  SectionHeader, ToggleRow, Field, SaveBar, InfoBanner, inputCls,
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
  const headerChanged = cfg.tenantIdHeader !== DEFAULT_TENANT_HEADER && cfg.tenantIdHeader !== initial.tenantIdHeader
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
        description="Multi-tenancy enforcement — ensures each tenant can only access their own routes via the X-Tenant-Id header predicate."
      />

      <InfoBanner variant="info">
        <div className="font-semibold text-indigo-300 mb-1.5">How tenant isolation works</div>
        <p className="leading-relaxed">
          Every route is compiled with a{' '}
          <code className="bg-white/10 px-1 rounded">Header=X-Tenant-Id,^&lt;uuid&gt;$</code> predicate.
          Only requests with the exact matching tenant UUID in{' '}
          <code className="bg-white/10 px-1 rounded">X-Tenant-Id</code> can match that route.
          The <span className="text-indigo-300">TenantContext</span> filter cross-validates against the JWT{' '}
          <code className="bg-white/10 px-1 rounded">tenantId</code> claim and rejects mismatches with{' '}
          <code className="bg-white/10 px-1 rounded">403 TENANT_MISMATCH</code>.
        </p>
        <p className="text-amber-400/80 mt-2 leading-relaxed">
          ⚠ These settings are stored in the database and broadcast to all gateway pods via Kafka.
          Disabling isolation or changing the header name requires all active routes to be reloaded
          and all API clients to be updated simultaneously.
        </p>
      </InfoBanner>

      <div className="mt-6 space-y-1">
        {/* Master toggle with a danger-ring variant when off */}
        <div className={cn(
          'flex items-center justify-between py-3.5 px-4 rounded-xl border transition-colors',
          cfg.enabled
            ? 'bg-emerald-500/5 border-emerald-500/20'
            : 'bg-red-500/[0.07] border-red-500/30',
        )}>
          <div className="mr-4">
            <div className="text-sm font-semibold text-white">Tenant Isolation Enabled</div>
            <div className="text-xs mt-0.5">
              {cfg.enabled
                ? <span className="text-emerald-400">All routes enforce tenant-scoped access — recommended for production</span>
                : <span className="text-red-400 font-medium">⚠ Disabled — any client can reach any tenant's routes without a matching header</span>
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
                : 'bg-red-700 focus-visible:ring-red-500',
            )}
          >
            <span className={cn(
              'absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform duration-150',
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
            hint={`Default: ${DEFAULT_TENANT_HEADER}. Changing this requires updating all route predicates and every API client simultaneously — full gateway reload required.`}
          >
            <div className="flex items-center gap-2">
              <input
                value={cfg.tenantIdHeader}
                onChange={e => setCfg(p => ({ ...p, tenantIdHeader: e.target.value }))}
                className={cn(
                  'w-64',
                  inputCls,
                  headerChanged ? 'border-amber-500/60 focus:border-amber-400' : '',
                )}
              />
              {cfg.tenantIdHeader !== DEFAULT_TENANT_HEADER && (
                <button
                  type="button"
                  onClick={() => setCfg(p => ({ ...p, tenantIdHeader: DEFAULT_TENANT_HEADER }))}
                  className="text-xs text-gray-400 hover:text-white underline transition-colors"
                >
                  Reset to default
                </button>
              )}
            </div>
            {headerChanged && (
              <p className="text-xs text-amber-400 mt-1.5">
                ⚠ Changing the header name will break all existing routes until they are reloaded and all clients are updated.
              </p>
            )}
          </Field>
        </div>

        {/* Inconsistent state warning */}
        {!cfg.enabled && cfg.enforceHeaderPredicate && (
          <InfoBanner variant="warning">
            Inconsistent state: isolation is disabled globally but header predicate enforcement is still on.
            Route compilation will still add the header predicate, but the TenantContext validation filter
            will not reject mismatches.
          </InfoBanner>
        )}
      </div>

      {isDisabling && dirty && (
        <div className="mt-4">
          <InfoBanner variant="danger">
            You are about to save a configuration that <strong>disables tenant isolation</strong>.
            This will take effect on all gateway pods after the next Kafka reload event.
          </InfoBanner>
        </div>
      )}

      {/* Disable confirmation dialog */}
      {confirmDisable && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-[#13151a] border border-red-500/30 rounded-xl w-full max-w-md shadow-2xl p-6 space-y-4">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-red-500/10 border border-red-500/20 flex items-center justify-center shrink-0">
                <AlertTriangle className="w-5 h-5 text-red-400" />
              </div>
              <h3 className="text-base font-semibold text-white">Disable Tenant Isolation?</h3>
            </div>
            <p className="text-sm text-gray-400 leading-relaxed">
              Disabling tenant isolation means <strong className="text-white">any authenticated client can reach routes belonging to any tenant</strong>{' '}
              simply by omitting the <code className="bg-white/10 px-1 rounded">X-Tenant-Id</code> header.
            </p>
            <p className="text-sm text-red-400 leading-relaxed">
              This is a critical security change. Only disable in controlled development environments.
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
                className="px-4 py-2 bg-red-600 hover:bg-red-500 text-white text-sm font-medium rounded-lg transition-colors"
              >
                Disable Isolation
              </button>
            </div>
          </div>
        </div>
      )}

      <SaveBar onSave={() => onSave(cfg)} isPending={isPending} dirty={dirty} />
    </div>
  )
}

