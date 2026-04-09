import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import { ShieldCheck, Plus, Globe, Loader2, AlertTriangle, CheckCircle2, RefreshCw } from 'lucide-react'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { certVaultApi } from '../../api/certVaultApi'
import { extractApiError } from '../../lib/utils'
import { useAuthStore } from '../../store/authStore'
import type { AcmeOrderDto, CertGroupDto } from '../../types'
import AcmeSetupModal from './AcmeSetupModal'
import AcmeOrderRow from './AcmeOrderRow'
import { toast } from 'sonner'

const issueSchema = z.object({
  domain: z.string().min(1, 'Domain is required'),
  certGroupId: z.string().optional(),
  autoRenew: z.boolean(),
})

type IssueFormValues = z.infer<typeof issueSchema>

// ─── Renewal Timeline Chart ───────────────────────────────────────────────────

function computeChartData(orders: AcmeOrderDto[]) {
  const now = Date.now()
  return orders
    .filter((o) => o.status === 'COMPLETED' && o.nextRenewalAt)
    .map((o) => ({
      domain: o.domain.length > 20 ? o.domain.slice(0, 20) + '…' : o.domain,
      daysUntilRenewal: Math.max(0, Math.ceil((new Date(o.nextRenewalAt!).getTime() - now) / 86_400_000)),
    }))
}

function RenewalTimeline({ chartData }: { chartData: ReturnType<typeof computeChartData> }) {
  if (chartData.length === 0) return null

  return (
    <div className="rounded-xl border border-white/6 bg-white/2 p-4 mb-5">
      <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3 flex items-center gap-1.5">
        <RefreshCw className="w-3.5 h-3.5" />
        Renewal Timeline
      </h3>
      <ResponsiveContainer width="100%" height={180}>
        <BarChart data={chartData} layout="vertical">
          <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
          <XAxis
            type="number"
            tick={{ fill: '#6b7280', fontSize: 10 }}
            label={{ value: 'Days until renewal', position: 'bottom', fill: '#6b7280', fontSize: 10 }}
          />
          <YAxis type="category" dataKey="domain" tick={{ fill: '#9ca3af', fontSize: 10 }} width={130} />
          <Tooltip
            contentStyle={{ background: '#1a1d27', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 8 }}
            labelStyle={{ color: '#fff', fontSize: 12 }}
            itemStyle={{ color: '#34d399', fontSize: 11 }}
          />
          <Bar dataKey="daysUntilRenewal" fill="#34d399" radius={[0, 4, 4, 0]} name="Days" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

// ─── Issue Certificate Form ───────────────────────────────────────────────────

function IssueCertForm({
  tenantId,
  accountId,
  groups,
  onSuccess,
}: {
  tenantId: string
  accountId: string
  groups: CertGroupDto[]
  onSuccess: () => void
}) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<IssueFormValues>({
    resolver: zodResolver(issueSchema),
    defaultValues: { domain: '', certGroupId: '', autoRenew: true },
  })

  const mutation = useMutation({
    mutationFn: (values: IssueFormValues) =>
      certVaultApi.issueAcmeCertificate(tenantId, {
        accountId,
        domain: values.domain,
        certGroupId: values.certGroupId || undefined,
      }),
    onSuccess: () => {
      toast.success('Certificate issuance started')
      reset()
      onSuccess()
    },
    onError: (err) => {
      toast.error(extractApiError(err))
    },
  })

  return (
    <form
      onSubmit={handleSubmit((v) => mutation.mutate(v))}
      className="rounded-xl border border-white/6 bg-white/2 p-4 mb-5"
    >
      <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-3 flex items-center gap-1.5">
        <Globe className="w-3.5 h-3.5" />
        Issue Certificate
      </h3>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <div>
          <label className="block text-[10px] font-medium text-gray-500 mb-1" htmlFor="field-domain-0">
            Domain
          </label>
          <input
            id="field-domain-0"
            {...register('domain')}
            placeholder="api.example.com"
            className="w-full px-2.5 py-1.5 rounded-lg bg-white/5 border border-white/10 text-xs text-white placeholder-gray-600 focus:border-indigo-500/50 focus:ring-1 focus:ring-indigo-500/30 outline-none"
          />
          {errors.domain && <p className="text-[10px] text-red-400 mt-0.5">{errors.domain.message}</p>}
        </div>
        <div>
          <label className="block text-[10px] font-medium text-gray-500 mb-1" htmlFor="field-target-cert-group-1">
            Target Cert Group
          </label>
          <select
            id="field-target-cert-group-1"
            {...register('certGroupId')}
            className="w-full px-2.5 py-1.5 rounded-lg bg-white/5 border border-white/10 text-xs text-white focus:border-indigo-500/50 outline-none"
          >
            <option value="">— None —</option>
            {groups
              .filter((g) => g.status === 'ACTIVE')
              .map((g) => (
                <option key={g.id} value={g.id}>
                  {g.alias} ({g.logicalId})
                </option>
              ))}
          </select>
        </div>
        <div className="flex items-end">
          <button
            type="submit"
            disabled={mutation.isPending}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-white bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 transition-all shadow-lg shadow-indigo-500/20"
          >
            {mutation.isPending ? <Loader2 className="w-3 h-3 animate-spin" /> : <Plus className="w-3 h-3" />}
            Issue Certificate
          </button>
        </div>
      </div>
    </form>
  )
}

// ─── Main ACME Tab ────────────────────────────────────────────────────────────

export default function AcmeTab() {
  const { user } = useAuthStore()
  const tenantId = user?.tenantId ?? ''
  const qc = useQueryClient()
  const [showSetup, setShowSetup] = useState(false)

  // Fetch ACME orders
  const { data: ordersData, isLoading: ordersLoading } = useRealtimeQuery({
    queryKey: ['acme-orders', tenantId],
    queryFn: () => certVaultApi.listAcmeOrders({ tenantId, size: 50 }),
    enabled: !!tenantId,
    wsEvents: ['certificate'],
  })

  // Fetch cert groups for the issue form
  const { data: groupsData } = useRealtimeQuery({
    queryKey: ['cert-groups-acme', tenantId],
    queryFn: () => certVaultApi.listGroups({ tenantId, size: 100 }),
    enabled: !!tenantId,
    wsEvents: ['certificate'],
  })

  const orders = ordersData?.content ?? []
  const groups = groupsData?.content ?? []

  // Stats
  const completed = orders.filter((o) => o.status === 'COMPLETED').length
  const failed = orders.filter((o) => o.status === 'FAILED' || o.status === 'RENEWAL_FAILED').length
  const pending = orders.filter((o) => o.status === 'PENDING' || o.status === 'VALIDATING').length

  // Use first account as the "active" one (simplified — could be extended to account picker)
  const hasAccount = orders.length > 0
  // We'll use a placeholder accountId from first order's account, or prompt setup
  const accountId = orders.length > 0 ? 'active' : ''

  return (
    <div className="h-full overflow-y-auto p-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between mb-5">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center">
            <ShieldCheck className="w-5 h-5 text-emerald-400" />
          </div>
          <div>
            <h2 className="text-base font-bold text-white">ACME / Auto-Renew</h2>
            <p className="text-xs text-gray-500">Automated certificate lifecycle via Let&apos;s Encrypt / ZeroSSL (staging available for testing)</p>
          </div>
        </div>
        <button
          onClick={() => setShowSetup(true)}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 hover:bg-emerald-500/20 transition-all"
        >
          <Plus className="w-3.5 h-3.5" />
          Register Account
        </button>
      </div>

      {/* Stats */}
      {orders.length > 0 && (
        <div className="flex items-center gap-3 mb-5">
          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg border bg-white/3 border-white/8 text-[11px]">
            <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
            <span className="text-gray-500">Active:</span>
            <span className="font-semibold text-emerald-300">{completed}</span>
          </div>
          {pending > 0 && (
            <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg border bg-blue-500/8 border-blue-500/20 text-[11px]">
              <Loader2 className="w-3.5 h-3.5 text-blue-400 animate-spin" />
              <span className="text-gray-500">Pending:</span>
              <span className="font-semibold text-blue-300">{pending}</span>
            </div>
          )}
          {failed > 0 && (
            <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg border bg-red-500/8 border-red-500/20 text-[11px]">
              <AlertTriangle className="w-3.5 h-3.5 text-red-400" />
              <span className="text-gray-500">Failed:</span>
              <span className="font-semibold text-red-300">{failed}</span>
            </div>
          )}
        </div>
      )}

      {/* Renewal Timeline */}
      <RenewalTimeline chartData={computeChartData(orders)} />

      {/* Issue Certificate Form — only show if we have an account context */}
      {hasAccount && (
        <IssueCertForm
          tenantId={tenantId}
          accountId={accountId}
          groups={groups}
          onSuccess={() => qc.invalidateQueries({ queryKey: ['acme-orders'] })}
        />
      )}

      {/* Order List */}
      <div className="flex items-center gap-2 mb-3">
        <Globe className="w-4 h-4 text-gray-500" />
        <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">ACME Orders ({orders.length})</h3>
      </div>

      {ordersLoading ? (
        <div className="flex items-center justify-center py-12">
          <div className="w-6 h-6 border-2 border-emerald-500/30 border-t-emerald-500 rounded-full animate-spin" />
        </div>
      ) : orders.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 gap-4 text-center border border-white/4 rounded-xl bg-white/1">
          <div className="w-16 h-16 rounded-2xl bg-white/3 border border-white/6 flex items-center justify-center">
            <Globe className="w-7 h-7 text-gray-600" />
          </div>
          <div>
            <p className="text-sm font-semibold text-gray-200 mb-1">No ACME certificates yet</p>
            <p className="text-xs text-gray-500 leading-relaxed max-w-sm">
              Register an ACME account, then issue certificates for your domains. They&apos;ll be auto-renewed 30 days
              before expiry.
            </p>
          </div>
          <button
            onClick={() => setShowSetup(true)}
            className="flex items-center gap-2 px-3.5 py-2 bg-emerald-600/20 hover:bg-emerald-600/30 border border-emerald-500/30 text-emerald-400 text-xs font-semibold rounded-lg transition-all"
          >
            <Plus className="w-3.5 h-3.5" />
            Get Started
          </button>
        </div>
      ) : (
        <div className="space-y-2">
          {orders.map((order) => (
            <AcmeOrderRow key={order.id} order={order} tenantId={tenantId} />
          ))}
        </div>
      )}

      {/* Setup Modal */}
      {showSetup && (
        <AcmeSetupModal
          tenantId={tenantId}
          onClose={() => setShowSetup(false)}
          onSuccess={() => {
            setShowSetup(false)
            qc.invalidateQueries({ queryKey: ['acme-orders'] })
          }}
        />
      )}
    </div>
  )
}
