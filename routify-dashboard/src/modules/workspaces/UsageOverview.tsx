import { useQuery } from '@tanstack/react-query'
import { tenantsApi } from '../../api/tenantsApi'
import type { QuotaDimension, TenantPlan } from '../../types'
import { cn } from '../../lib/utils'
import { Route, Filter, Zap, Loader2, AlertCircle } from 'lucide-react'

const PLAN_LABELS: Record<TenantPlan, string> = {
  FREE: 'Free',
  STARTER: 'Starter',
  PRO: 'Pro',
  ENTERPRISE: 'Enterprise',
}

function barColor(pct: number): string {
  if (pct >= 90) return 'bg-red-500'
  if (pct >= 70) return 'bg-amber-500'
  return 'bg-emerald-500'
}

function textColor(pct: number): string {
  if (pct >= 90) return 'text-red-400'
  if (pct >= 70) return 'text-amber-400'
  return 'text-emerald-400'
}

function QuotaBar({ label, icon: Icon, dim }: { label: string; icon: React.ElementType; dim: QuotaDimension }) {
  const isUnlimited = dim.limit >= 2_000_000_000 // Integer.MAX_VALUE
  const pct = isUnlimited ? 0 : dim.percentage

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between text-xs">
        <div className="flex items-center gap-1.5 text-gray-400 font-medium">
          <Icon className="w-3.5 h-3.5" />
          {label}
        </div>
        <span className={cn('font-semibold', isUnlimited ? 'text-gray-500' : textColor(pct))}>
          {isUnlimited ? `${dim.used.toLocaleString()} / ∞` : `${dim.used.toLocaleString()} / ${dim.limit.toLocaleString()}`}
        </span>
      </div>
      <div className="h-2 bg-white/[0.06] rounded-full overflow-hidden">
        <div
          className={cn('h-full rounded-full transition-all duration-500', isUnlimited ? 'bg-gray-600' : barColor(pct))}
          style={{ width: `${isUnlimited ? 0 : Math.min(100, pct)}%` }}
        />
      </div>
      {!isUnlimited && (
        <p className="text-[10px] text-gray-600 text-right">{pct}% used</p>
      )}
    </div>
  )
}

interface UsageOverviewProps {
  tenantId: string
}

export default function UsageOverview({ tenantId }: UsageOverviewProps) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['tenant-usage', tenantId],
    queryFn: () => tenantsApi.getUsage(tenantId),
    staleTime: 30_000,
    enabled: !!tenantId,
  })

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-gray-500 text-sm py-4">
        <Loader2 className="w-4 h-4 animate-spin" />
        Loading usage…
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="flex items-center gap-2 text-gray-500 text-sm py-4">
        <AlertCircle className="w-4 h-4" />
        Unable to load usage data.
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
          Usage — {PLAN_LABELS[data.plan]} Plan
        </h3>
        <span className="text-[10px] text-gray-600">
          {data.periodStart} – {data.periodEnd}
        </span>
      </div>

      <div className="grid gap-4">
        <QuotaBar label="Routes" icon={Route} dim={data.routes} />
        <QuotaBar label="Filters" icon={Filter} dim={data.filters} />
        <QuotaBar label="Monthly Requests" icon={Zap} dim={data.requests} />
      </div>
    </div>
  )
}

