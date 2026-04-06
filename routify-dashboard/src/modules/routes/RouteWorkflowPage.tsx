import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, TrendingUp } from 'lucide-react'
import { routesApi } from '../../api/routesApi'
import { useWsStore } from '../../store/wsStore'
import type { RouteStatus } from '../../types'
import RouteFormModal from './RouteFormModal'
import RouteDetailModal from './RouteDetailModal'
import RouteCurlModal from './RouteCurlModal'
import PromoteDiffModal from './components/PromoteDiffModal'
import { useRouteActions } from './useRouteActions'
import { STATUS_CONFIG } from './constants/routeStatusConfig'
import { type StatusFilterTab } from './routeConstants'
import RouteListHeader, { type EnvironmentFilterTab } from './components/RouteListHeader'
import RouteWorkflowCard from './components/RouteWorkflowCard'
import RoutePagination from './components/RoutePagination'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'

export default function RouteWorkflowPage() {
  useDocumentTitle('Routes')
  const qc = useQueryClient()
  const [statusFilter, setStatusFilter] = useState<StatusFilterTab>('')
  const [environmentFilter, setEnvironmentFilter] = useState<EnvironmentFilterTab>('')
  const [page, setPage] = useState(0)
  const [formModal, setFormModal] = useState<{ open: boolean; editingId?: string }>({ open: false })
  const [selectedRouteId, setSelectedRouteId] = useState<string | null>(null)
  const [curlRouteId, setCurlRouteId] = useState<string | null>(null)
  const [promoteRoute, setPromoteRoute] = useState<{ id: string; name: string } | null>(null)

  const openCreate = () => setFormModal({ open: true, editingId: undefined })
  const openEdit = (id: string) => setFormModal({ open: true, editingId: id })
  const closeForm = () => setFormModal({ open: false })

  const wsStatus = useWsStore((s) => s.status)

  const { data, isLoading, isFetching, refetch } = useRealtimeQuery({
    queryKey: ['routes', statusFilter, environmentFilter, page],
    queryFn: () =>
      routesApi.list({
        ...(statusFilter ? { status: statusFilter } : {}),
        ...(environmentFilter ? { environment: environmentFilter } : {}),
        page,
        size: 20,
        sortBy: 'createdAt',
        sortDir: 'DESC',
      }),
    wsEvents: ['route'],
  })

  const { data: curlRoute } = useQuery({
    queryKey: ['route', curlRouteId],
    queryFn: () => routesApi.get(curlRouteId!),
    enabled: !!curlRouteId,
  })

  const { activateMutation, deactivateMutation, deleteMutation, cloneMutation } = useRouteActions()

  const routes = data?.content ?? []
  const total = data?.totalElements ?? 0
  const totalPages = data?.totalPages ?? 0

  return (
    <div className="flex flex-col h-full animate-fade-in">
      <RouteListHeader
        total={total}
        routes={routes}
        statusFilter={statusFilter}
        environmentFilter={environmentFilter}
        isFetching={isFetching}
        isLive={wsStatus === 'CONNECTED'}
        onStatusFilter={(s) => {
          setStatusFilter(s)
          setPage(0)
        }}
        onEnvironmentFilter={(e) => {
          setEnvironmentFilter(e)
          setPage(0)
        }}
        onRefresh={() => refetch()}
        onNew={openCreate}
      />

      <div className="flex-1 overflow-auto">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center py-24 gap-3">
            <div className="w-7 h-7 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
            <p className="text-sm text-gray-500">Loading routes…</p>
          </div>
        ) : routes.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-24 gap-4">
            <div className="w-16 h-16 rounded-2xl bg-white/[0.03] border border-white/[0.06] flex items-center justify-center">
              <TrendingUp className="w-7 h-7 text-gray-600" />
            </div>
            <div className="text-center">
              <p className="text-sm font-medium text-gray-300 mb-1">No routes found</p>
              <p className="text-xs text-gray-600">
                {statusFilter
                  ? `No ${STATUS_CONFIG[statusFilter as RouteStatus].label.toLowerCase()} routes`
                  : 'Create your first route to get started'}
              </p>
            </div>
            {!statusFilter && (
              <button
                onClick={openCreate}
                className="flex items-center gap-2 px-4 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-sm font-medium rounded-lg transition-all"
              >
                <Plus className="w-4 h-4" /> Create first route
              </button>
            )}
          </div>
        ) : (
          <div className="p-6 grid grid-cols-1 xl:grid-cols-2 gap-4">
            {routes.map((route, i) => (
              <RouteWorkflowCard
                key={route.id}
                route={route}
                index={i}
                onSelect={() => setSelectedRouteId(route.id)}
                onEdit={() => openEdit(route.id)}
                onCurl={() => setCurlRouteId(route.id)}
                onClone={() => cloneMutation.mutate(route.id)}
                onActivate={() => activateMutation.mutate(route.id)}
                onDeactivate={() => deactivateMutation.mutate(route.id)}
                onDelete={() => deleteMutation.mutate(route.id)}
                onPromote={route.environment === 'STAGING' && route.status === 'ACTIVE'
                  ? () => setPromoteRoute({ id: route.id, name: route.name })
                  : undefined}
                isActivating={activateMutation.isPending && activateMutation.variables === route.id}
                isCloning={cloneMutation.isPending && cloneMutation.variables === route.id}
              />
            ))}
          </div>
        )}
      </div>

      <RoutePagination page={page} totalPages={totalPages} total={total} onPage={setPage} />

      {formModal.open && (
        <RouteFormModal
          editingId={formModal.editingId}
          onClose={closeForm}
          onSaved={() => {
            closeForm()
            qc.invalidateQueries({ queryKey: ['routes'] })
          }}
        />
      )}
      {selectedRouteId && <RouteDetailModal routeId={selectedRouteId} onClose={() => setSelectedRouteId(null)} />}
      {curlRouteId && curlRoute && <RouteCurlModal route={curlRoute} onClose={() => setCurlRouteId(null)} />}
      {promoteRoute && <PromoteDiffModal stagingRoute={promoteRoute} onClose={() => setPromoteRoute(null)} />}
    </div>
  )
}
