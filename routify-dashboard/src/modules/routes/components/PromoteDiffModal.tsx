/**
 * PromoteDiffModal — shows a staging-vs-production diff preview before promotion.
 */
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { X, ArrowUpRight, AlertCircle } from 'lucide-react'
import { toast } from 'sonner'
import { routesApi } from '../../../api/routesApi'
import { extractApiError } from '../../../lib/utils'
import EnvironmentBadge from './EnvironmentBadge'

interface Props {
  stagingRoute: { id: string; name: string }
  onClose: () => void
}

function DiffRow({ label, staging, production }: { label: string; staging?: string; production?: string }) {
  const changed = staging !== production
  return (
    <tr className={changed ? 'bg-amber-500/[0.04]' : ''}>
      <td className="px-3 py-2 text-xs font-medium text-gray-500 whitespace-nowrap">{label}</td>
      <td className="px-3 py-2 text-xs text-gray-400 font-mono break-all">{production ?? '—'}</td>
      <td className="px-3 py-2 text-xs text-white font-mono break-all">{staging ?? '—'}</td>
      <td className="px-3 py-2 text-center">
        {changed && <span className="text-[10px] text-amber-400 font-semibold">Changed</span>}
      </td>
    </tr>
  )
}

export default function PromoteDiffModal({ stagingRoute, onClose }: Props) {
  const qc = useQueryClient()

  const { data: stagingData, isLoading: loadingStaging } = useQuery({
    queryKey: ['route', stagingRoute.id],
    queryFn: () => routesApi.get(stagingRoute.id),
  })

  // Try to find the production counterpart (same name, PRODUCTION env)
  const { data: productionList } = useQuery({
    queryKey: ['routes', 'production-lookup', stagingRoute.name],
    queryFn: () => routesApi.list({ environment: 'PRODUCTION', size: 100 }),
    enabled: !!stagingData,
  })

  const productionSummary = productionList?.content?.find(
    (r) => r.name === stagingRoute.name,
  )

  // Fetch full production route detail (RouteSummary from list lacks stripPrefix and filters)
  const { data: productionRoute, isLoading: loadingProduction } = useQuery({
    queryKey: ['route', productionSummary?.id],
    queryFn: () => routesApi.get(productionSummary!.id),
    enabled: !!productionSummary?.id,
  })

  const promoteMutation = useMutation({
    mutationFn: () => routesApi.promote(stagingRoute.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['routes'] })
      toast.success('Route promoted to production', {
        description: 'Gateway will reload within seconds.',
      })
      onClose()
    },
    onError: (err) => {
      toast.error(extractApiError(err, 'Promotion failed'))
    },
  })

  const isLoading = loadingStaging || (!!productionSummary && loadingProduction)

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4 animate-fade-in">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-3xl shadow-2xl flex flex-col max-h-[92vh] animate-fade-in-up">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06] shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-lg bg-amber-500/20 border border-amber-500/30 flex items-center justify-center shrink-0">
              <ArrowUpRight className="w-3.5 h-3.5 text-amber-400" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-white leading-tight">Promote to Production</h2>
              <p className="text-[11px] text-gray-500 mt-0.5">{stagingRoute.name}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-6">
          {isLoading ? (
            <div className="flex items-center justify-center py-12 text-gray-500 text-sm">Loading diff…</div>
          ) : (
            <>
              <div className="flex items-center gap-3 mb-4">
                <EnvironmentBadge environment="STAGING" />
                <span className="text-gray-600 text-xs">→</span>
                <EnvironmentBadge environment="PRODUCTION" />
              </div>

              {!productionRoute && (
                <div className="flex items-start gap-2.5 p-3 mb-4 bg-indigo-500/[0.06] border border-indigo-500/20 rounded-xl text-xs text-indigo-300">
                  <AlertCircle className="w-4 h-4 mt-0.5 shrink-0 text-indigo-400" />
                  No existing production route found — a new production route will be created.
                </div>
              )}

              <div className="border border-white/[0.06] rounded-lg overflow-hidden">
                <table className="w-full text-left">
                  <thead>
                    <tr className="bg-white/[0.02] border-b border-white/[0.06]">
                      <th className="px-3 py-2 text-[10px] font-semibold text-gray-500 uppercase">Field</th>
                      <th className="px-3 py-2 text-[10px] font-semibold text-gray-500 uppercase">Production</th>
                      <th className="px-3 py-2 text-[10px] font-semibold text-gray-500 uppercase">Staging</th>
                      <th className="px-3 py-2 text-[10px] font-semibold text-gray-500 uppercase text-center">
                        Status
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-white/[0.04]">
                    <DiffRow
                      label="Path Pattern"
                      staging={stagingData?.pathPattern}
                      production={productionRoute?.pathPattern}
                    />
                    <DiffRow label="Methods" staging={stagingData?.methods} production={productionRoute?.methods} />
                    <DiffRow
                      label="Upstream URI"
                      staging={stagingData?.upstreamUri}
                      production={productionRoute?.upstreamUri}
                    />
                    <DiffRow
                      label="Strip Prefix"
                      staging={stagingData?.stripPrefix}
                      production={productionRoute?.stripPrefix}
                    />
                    <DiffRow
                      label="Description"
                      staging={stagingData?.description}
                      production={productionRoute?.description}
                    />
                    <DiffRow
                      label="Filters"
                      staging={`${stagingData?.filters?.length ?? 0} attached`}
                      production={productionRoute?.filters ? `${productionRoute.filters.length} attached` : '—'}
                    />
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-6 py-4 border-t border-white/[0.06] shrink-0">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => promoteMutation.mutate()}
            disabled={promoteMutation.isPending || isLoading}
            className="px-4 py-2 bg-amber-600 hover:bg-amber-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-amber-500/20"
          >
            {promoteMutation.isPending ? 'Promoting…' : 'Promote to Production'}
          </button>
        </div>
      </div>
    </div>
  )
}
