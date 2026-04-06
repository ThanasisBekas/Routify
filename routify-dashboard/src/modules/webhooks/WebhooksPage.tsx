import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  Bell,
  Plus,
  Trash2,
  TestTube,
  ChevronLeft,
  ChevronRight,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Loader2,
} from 'lucide-react'
import { webhooksApi } from '../../api/webhooksApi'
import { extractApiError, cn } from '../../lib/utils'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import WebhookFormModal from './WebhookFormModal'
import WebhookDeliveryLog from './WebhookDeliveryLog'
import type { WebhookSubscriptionDto } from '../../types'

export default function WebhooksPage() {
  useDocumentTitle('Webhooks')
  const qc = useQueryClient()
  const [page, setPage] = useState(0)
  const [showCreate, setShowCreate] = useState(false)
  const [showDeliveries, setShowDeliveries] = useState<string | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['webhooks', page],
    queryFn: () => webhooksApi.list({ page, size: 20 }),
  })

  const deleteMut = useMutation({
    mutationFn: (id: string) => webhooksApi.delete(id),
    onSuccess: () => {
      toast.success('Webhook subscription deleted')
      qc.invalidateQueries({ queryKey: ['webhooks'] })
    },
    onError: (e) => toast.error(extractApiError(e)),
  })

  const testMut = useMutation({
    mutationFn: (id: string) => webhooksApi.testPing(id),
    onSuccess: (result) => {
      if (result.success) {
        toast.success(`Test ping succeeded (HTTP ${result.responseStatus})`)
      } else {
        toast.error(`Test ping failed: ${result.message}`)
      }
    },
    onError: (e) => toast.error(extractApiError(e)),
  })

  const statusBadge = (sub: WebhookSubscriptionDto) => {
    if (sub.status === 'ACTIVE') {
      return (
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
          <CheckCircle2 className="w-3 h-3" /> Active
        </span>
      )
    }
    if (sub.status === 'SUSPENDED') {
      return (
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-amber-500/10 text-amber-400 border border-amber-500/20">
          <AlertTriangle className="w-3 h-3" /> Suspended
          {sub.failureCount > 0 && ` (${sub.failureCount} failures)`}
        </span>
      )
    }
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-gray-500/10 text-gray-400 border border-gray-500/20">
        {sub.status}
      </span>
    )
  }

  return (
    <div className="flex-1 overflow-auto p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-lg bg-indigo-500/10 border border-indigo-500/20">
            <Bell className="w-5 h-5 text-indigo-400" />
          </div>
          <div>
            <h1 className="text-lg font-bold text-white">Webhooks</h1>
            <p className="text-xs text-gray-500">Manage webhook subscriptions for platform events</p>
          </div>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 px-3 py-2 rounded-lg bg-indigo-500/10 text-indigo-300 border border-indigo-500/20 hover:bg-indigo-500/20 text-sm font-medium transition-colors"
        >
          <Plus className="w-4 h-4" /> New Webhook
        </button>
      </div>

      {/* Table */}
      {isLoading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-6 h-6 text-indigo-500 animate-spin" />
        </div>
      ) : !data || data.content.length === 0 ? (
        <div className="text-center py-20 text-gray-500 text-sm">
          No webhook subscriptions yet. Create one to get started.
        </div>
      ) : (
        <>
          <div className="border border-white/[0.06] rounded-xl overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-white/[0.02] border-b border-white/[0.06]">
                  <th className="text-left px-4 py-3 text-gray-500 font-medium text-xs uppercase tracking-wider">
                    Name
                  </th>
                  <th className="text-left px-4 py-3 text-gray-500 font-medium text-xs uppercase tracking-wider">
                    URL
                  </th>
                  <th className="text-left px-4 py-3 text-gray-500 font-medium text-xs uppercase tracking-wider">
                    Events
                  </th>
                  <th className="text-left px-4 py-3 text-gray-500 font-medium text-xs uppercase tracking-wider">
                    Status
                  </th>
                  <th className="text-left px-4 py-3 text-gray-500 font-medium text-xs uppercase tracking-wider">
                    Last Delivered
                  </th>
                  <th className="text-right px-4 py-3 text-gray-500 font-medium text-xs uppercase tracking-wider">
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((sub) => (
                  <tr
                    key={sub.id}
                    className="border-b border-white/[0.04] hover:bg-white/[0.02] transition-colors cursor-pointer"
                    onClick={() => setShowDeliveries(sub.id)}
                  >
                    <td className="px-4 py-3 font-medium text-white">{sub.name}</td>
                    <td className="px-4 py-3 text-gray-400 max-w-[200px] truncate font-mono text-xs">
                      {sub.url}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1">
                        {sub.eventTypes.slice(0, 3).map((et) => (
                          <span
                            key={et}
                            className="px-1.5 py-0.5 rounded text-[10px] font-medium bg-white/[0.05] text-gray-400 border border-white/[0.06]"
                          >
                            {et}
                          </span>
                        ))}
                        {sub.eventTypes.length > 3 && (
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-medium bg-white/[0.05] text-gray-500">
                            +{sub.eventTypes.length - 3}
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3">{statusBadge(sub)}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs">
                      {sub.lastDeliveredAt ? (
                        <span className="flex items-center gap-1">
                          <Clock className="w-3 h-3" />
                          {new Date(sub.lastDeliveredAt).toLocaleString()}
                        </span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td className="px-4 py-3 text-right" onClick={(e) => e.stopPropagation()}>
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => testMut.mutate(sub.id)}
                          disabled={testMut.isPending}
                          title="Test Ping"
                          className="p-1.5 rounded-md text-gray-500 hover:text-indigo-400 hover:bg-indigo-500/10 transition-colors"
                        >
                          <TestTube className="w-3.5 h-3.5" />
                        </button>
                        <button
                          onClick={() => {
                            if (confirm('Delete this webhook subscription?')) {
                              deleteMut.mutate(sub.id)
                            }
                          }}
                          disabled={deleteMut.isPending}
                          title="Delete"
                          className="p-1.5 rounded-md text-gray-500 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {data.totalPages > 1 && (
            <div className="flex items-center justify-between mt-4 text-xs text-gray-500">
              <span>
                Page {page + 1} of {data.totalPages} ({data.totalElements} total)
              </span>
              <div className="flex gap-1">
                <button
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className={cn(
                    'p-1 rounded',
                    page === 0 ? 'opacity-30' : 'hover:bg-white/[0.05]',
                  )}
                >
                  <ChevronLeft className="w-4 h-4" />
                </button>
                <button
                  onClick={() => setPage((p) => p + 1)}
                  disabled={page + 1 >= data.totalPages}
                  className={cn(
                    'p-1 rounded',
                    page + 1 >= data.totalPages ? 'opacity-30' : 'hover:bg-white/[0.05]',
                  )}
                >
                  <ChevronRight className="w-4 h-4" />
                </button>
              </div>
            </div>
          )}
        </>
      )}

      {/* Create modal */}
      {showCreate && <WebhookFormModal onClose={() => setShowCreate(false)} />}

      {/* Delivery log drawer */}
      {showDeliveries && (
        <WebhookDeliveryLog
          subscriptionId={showDeliveries}
          onClose={() => setShowDeliveries(null)}
        />
      )}
    </div>
  )
}

