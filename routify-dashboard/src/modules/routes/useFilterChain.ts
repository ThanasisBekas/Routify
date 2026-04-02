import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { routesApi } from '../../api/routesApi'
import { filtersApi } from '../../api/filtersApi'
import type { AttachFilterRequest } from '../../types'

/**
 * Manages the filter-attachment / detachment state and mutations for a single route.
 * Keeps the RouteDetailDrawer free of mutation plumbing.
 */
export function useFilterChain(routeId: string) {
  const qc = useQueryClient()

  const [attachMode, setAttachMode] = useState(false)
  const [selectedFilter, setSelectedFilter] = useState<string>('')
  const [filterOrder, setFilterOrder] = useState(10)
  const [filterPhase, setFilterPhase] = useState<'PRE' | 'POST'>('PRE')

  const invalidateRoute = () => {
    qc.invalidateQueries({ queryKey: ['route', routeId] })
  }

  const { data: availableFilters } = useQuery({
    queryKey: ['filters-list'],
    queryFn: () => filtersApi.list({ size: 100 }),
    enabled: attachMode,
  })

  const attachMutation = useMutation({
    mutationFn: (req: AttachFilterRequest) => routesApi.attachFilter(routeId, req),
    onSuccess: () => {
      invalidateRoute()
      setAttachMode(false)
      setSelectedFilter('')
    },
  })

  const detachMutation = useMutation({
    mutationFn: (filterId: string) => routesApi.detachFilter(routeId, filterId),
    onSuccess: invalidateRoute,
  })

  const attach = () =>
    attachMutation.mutate({ filterId: selectedFilter, order: filterOrder, phase: filterPhase })

  const cancelAttach = () => {
    setAttachMode(false)
    setSelectedFilter('')
  }

  return {
    // state
    attachMode,
    selectedFilter,
    filterOrder,
    filterPhase,
    availableFilters: availableFilters?.content ?? [],
    isAttaching: attachMutation.isPending,
    // actions
    openAttach: () => setAttachMode(true),
    toggleAttach: () => setAttachMode(v => !v),
    cancelAttach,
    setSelectedFilter,
    setFilterOrder,
    setFilterPhase,
    attach,
    detach: (filterId: string) => detachMutation.mutate(filterId),
  }
}

