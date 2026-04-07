import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  RefreshCw,
  CheckCircle2,
  Clock,
  AlertTriangle,
  XCircle,
  Loader2,
  Globe,
  Calendar,
} from 'lucide-react'
import { certVaultApi } from '../../api/certVaultApi'
import { extractApiError } from '../../lib/utils'
import { cn } from '../../lib/utils'
import type { AcmeOrderDto, AcmeOrderStatus } from '../../types'
import { toast } from 'sonner'

const STATUS_CONFIG: Record<AcmeOrderStatus, { icon: React.ReactNode; color: string; label: string }> = {
  PENDING: {
    icon: <Clock className="w-3 h-3" />,
    color: 'text-blue-400 bg-blue-400/10 border-blue-400/20',
    label: 'Pending',
  },
  VALIDATING: {
    icon: <Loader2 className="w-3 h-3 animate-spin" />,
    color: 'text-amber-400 bg-amber-400/10 border-amber-400/20',
    label: 'Validating',
  },
  COMPLETED: {
    icon: <CheckCircle2 className="w-3 h-3" />,
    color: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
    label: 'Completed',
  },
  FAILED: {
    icon: <XCircle className="w-3 h-3" />,
    color: 'text-red-400 bg-red-400/10 border-red-400/20',
    label: 'Failed',
  },
  RENEWAL_FAILED: {
    icon: <AlertTriangle className="w-3 h-3" />,
    color: 'text-orange-400 bg-orange-400/10 border-orange-400/20',
    label: 'Renewal Failed',
  },
}

function formatDate(iso?: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
}

export default function AcmeOrderRow({
  order,
  tenantId,
}: {
  order: AcmeOrderDto
  tenantId: string
}) {
  const qc = useQueryClient()
  const [expanded, setExpanded] = useState(false)
  const statusConfig = STATUS_CONFIG[order.status]

  const renewMutation = useMutation({
    mutationFn: () => certVaultApi.renewAcmeCertificate(order.id, tenantId),
    onSuccess: () => {
      toast.success(`Renewal triggered for ${order.domain}`)
      qc.invalidateQueries({ queryKey: ['acme-orders'] })
    },
    onError: (err) => {
      toast.error(extractApiError(err))
    },
  })

  return (
    <div className="rounded-xl border border-white/6 bg-white/2 overflow-hidden transition-all hover:border-white/10">
      {/* Main row */}
      <div
        role="button"
        tabIndex={0}
        onClick={() => setExpanded(!expanded)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            setExpanded(!expanded)
          }
        }}
        className="flex items-center gap-3 px-4 py-3 cursor-pointer"
      >
        <Globe className="w-4 h-4 text-indigo-400 shrink-0" />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-white truncate">{order.domain}</span>
            <span
              className={cn(
                'inline-flex items-center gap-1 text-[10px] font-medium px-1.5 py-0.5 rounded-full border',
                statusConfig.color,
              )}
            >
              {statusConfig.icon}
              {statusConfig.label}
            </span>
            {order.autoRenew && (
              <span className="text-[10px] text-emerald-400/70 bg-emerald-400/8 px-1.5 py-0.5 rounded-full border border-emerald-400/15">
                Auto-renew
              </span>
            )}
          </div>
          <div className="flex items-center gap-3 mt-0.5 text-[10px] text-gray-500">
            <span className="flex items-center gap-1">
              <Calendar className="w-2.5 h-2.5" />
              Created {formatDate(order.createdAt)}
            </span>
            {order.nextRenewalAt && (
              <span>
                Next renewal: <span className="text-amber-300">{formatDate(order.nextRenewalAt)}</span>
              </span>
            )}
          </div>
        </div>

        {/* Actions */}
        <div className="flex items-center gap-1.5 shrink-0">
          {(order.status === 'COMPLETED' || order.status === 'RENEWAL_FAILED') && (
            <button
              onClick={(e) => {
                e.stopPropagation()
                renewMutation.mutate()
              }}
              disabled={renewMutation.isPending}
              className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 hover:bg-emerald-500/20 disabled:opacity-50 transition-all"
            >
              {renewMutation.isPending ? (
                <Loader2 className="w-3 h-3 animate-spin" />
              ) : (
                <RefreshCw className="w-3 h-3" />
              )}
              Renew Now
            </button>
          )}
        </div>
      </div>

      {/* Expanded details */}
      {expanded && (
        <div className="px-4 pb-3 pt-0 border-t border-white/4 animate-fade-in">
          <div className="grid grid-cols-2 gap-3 mt-3 text-xs">
            <div>
              <span className="text-gray-500 block mb-0.5">Challenge Type</span>
              <span className="text-gray-300">{order.challengeType}</span>
            </div>
            <div>
              <span className="text-gray-500 block mb-0.5">Last Renewed</span>
              <span className="text-gray-300">{formatDate(order.lastRenewedAt)}</span>
            </div>
            {order.certId && (
              <div>
                <span className="text-gray-500 block mb-0.5">Certificate ID</span>
                <span className="text-indigo-300 font-mono text-[10px]">{order.certId}</span>
              </div>
            )}
            {order.errorMessage && (
              <div className="col-span-2">
                <span className="text-gray-500 block mb-0.5">Error</span>
                <span className="text-red-300 text-[11px]">{order.errorMessage}</span>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

