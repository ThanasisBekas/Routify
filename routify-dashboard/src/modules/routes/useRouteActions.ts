import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { routesApi } from '../../api/routesApi'
import { extractApiError } from '../../lib/utils'

/**
 * Encapsulates the activate / deactivate / delete / clone mutations for a route.
 * All automatically invalidate the ['routes'] query on success.
 *
 * Pass `routeId` when used inside a detail view (drawer/modal) so the single-route
 * query is also invalidated on success/error.
 */
export function useRouteActions(routeId?: string) {
  const qc = useQueryClient()

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['routes'] })
    if (routeId) qc.invalidateQueries({ queryKey: ['route', routeId] })
  }

  const activateMutation = useMutation({
    mutationFn: routesApi.activate,
    onSuccess: () => {
      invalidate()
      toast.success('Route activated', { description: 'The route is now live in the gateway.' })
    },
    onError: (error) => {
      const message = extractApiError(error)
      toast.error('Cannot activate route', { description: message })
    },
  })

  const deactivateMutation = useMutation({
    mutationFn: routesApi.deactivate,
    onSuccess: () => {
      invalidate()
      toast.success('Route deactivated', { description: 'The route has been removed from the gateway.' })
    },
    onError: (error) => {
      const message = extractApiError(error)
      toast.error('Cannot deactivate route', { description: message })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: routesApi.delete,
    onSuccess: invalidate,
    onError: (error) => {
      toast.error('Cannot delete route', { description: extractApiError(error) })
    },
  })

  const cloneMutation = useMutation({
    mutationFn: routesApi.clone,
    onSuccess: () => {
      invalidate()
      toast.success('Route cloned', { description: 'A draft copy has been created.' })
    },
    onError: (error) => {
      toast.error('Cannot clone route', { description: extractApiError(error) })
    },
  })

  return { activateMutation, deactivateMutation, deleteMutation, cloneMutation }
}

