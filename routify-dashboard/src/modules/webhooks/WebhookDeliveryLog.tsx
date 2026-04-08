import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { X, ChevronLeft, ChevronRight, CheckCircle2, XCircle, Clock, Loader2 } from 'lucide-react'
import { webhooksApi } from '../../api/webhooksApi'
import { cn } from '../../lib/utils'
import type { WebhookDeliveryDto } from '../../types'

interface Props {
  subscriptionId: string
  onClose: () => void
}

export default function WebhookDeliveryLog({ subscriptionId, onClose }: Props) {
  const [page, setPage] = useState(0)

  const { data, isLoading } = useQuery({
    queryKey: ['webhook-deliveries', subscriptionId, page],
    queryFn: () => webhooksApi.deliveries(subscriptionId, { page, size: 20 }),
  })

  const statusIcon = (d: WebhookDeliveryDto) => {
    if (d.status === 'DELIVERED') return <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
    if (d.status === 'FAILED') return <XCircle className="w-3.5 h-3.5 text-red-400" />
    return <Clock className="w-3.5 h-3.5 text-amber-400" />
  }

  const statusLabel = (d: WebhookDeliveryDto) => {
    if (d.status === 'DELIVERED') return <span className="text-emerald-400 font-medium">Delivered</span>
    if (d.status === 'FAILED') return <span className="text-red-400 font-medium">Failed</span>
    return <span className="text-amber-400 font-medium">Pending retry</span>
  }

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-end bg-black/40 backdrop-blur-sm">
      <div className="bg-[#0d0f14] border-l border-white/10 w-full max-w-xl h-full overflow-y-auto shadow-2xl">
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/[0.06] sticky top-0 bg-[#0d0f14] z-10">
          <h2 className="text-sm font-bold text-white">Delivery Log</h2>
          <button onClick={onClose} className="p-1 rounded hover:bg-white/[0.05] text-gray-500">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5">
          {isLoading ? (
            <div className="flex items-center justify-center py-20">
              <Loader2 className="w-5 h-5 text-indigo-500 animate-spin" />
            </div>
          ) : !data || data.content.length === 0 ? (
            <p className="text-center py-20 text-gray-500 text-sm">No delivery attempts yet.</p>
          ) : (
            <>
              <div className="space-y-3">
                {data.content.map((d) => (
                  <div
                    key={d.id}
                    className="border border-white/[0.06] rounded-lg p-3 bg-white/[0.01] hover:bg-white/[0.02] transition-colors"
                  >
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex items-center gap-2">
                        {statusIcon(d)}
                        {statusLabel(d)}
                        <span className="text-[10px] text-gray-600 font-mono">#{d.attempt}</span>
                      </div>
                      <span className="text-[10px] text-gray-600">{new Date(d.createdAt).toLocaleString()}</span>
                    </div>

                    <div className="flex items-center gap-3 text-xs text-gray-500 mb-1">
                      <span className="px-1.5 py-0.5 rounded bg-white/[0.04] border border-white/[0.06] font-mono text-[10px]">
                        {d.eventType}
                      </span>
                      {d.responseStatus && (
                        <span
                          className={cn(
                            'font-mono',
                            d.responseStatus >= 200 && d.responseStatus < 300 ? 'text-emerald-400' : 'text-red-400',
                          )}
                        >
                          HTTP {d.responseStatus}
                        </span>
                      )}
                    </div>

                    {d.errorMessage && (
                      <p className="text-xs text-red-400/80 mt-1 truncate" title={d.errorMessage}>
                        {d.errorMessage}
                      </p>
                    )}

                    {d.nextRetryAt && d.status === 'PENDING' && (
                      <p className="text-[10px] text-amber-500/70 mt-1">
                        Next retry: {new Date(d.nextRetryAt).toLocaleString()}
                      </p>
                    )}

                    {d.deliveredAt && (
                      <p className="text-[10px] text-emerald-500/70 mt-1">
                        Delivered at: {new Date(d.deliveredAt).toLocaleString()}
                      </p>
                    )}
                  </div>
                ))}
              </div>

              {/* Pagination */}
              {data.totalPages > 1 && (
                <div className="flex items-center justify-between mt-4 text-xs text-gray-500">
                  <span>
                    Page {page + 1} of {data.totalPages}
                  </span>
                  <div className="flex gap-1">
                    <button
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                      disabled={page === 0}
                      className={cn('p-1 rounded', page === 0 ? 'opacity-30' : 'hover:bg-white/[0.05]')}
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
        </div>
      </div>
    </div>
  )
}
