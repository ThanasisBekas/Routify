/**
 * useRealtimeQuery — React Query + WebSocket integration.
 *
 * Wraps useQuery and automatically invalidates the cache when a WebSocket
 * event arrives whose `queryKey` matches. This means:
 *
 *   - Route list refetches automatically when route.activated fires
 *   - Audit page gets new events without manual refresh
 *   - Gateway overview updates circuit breakers live from /topic/metrics
 *
 * Usage:
 *   const { data } = useRealtimeQuery({
 *     queryKey: ['routes'],
 *     queryFn: routesApi.list,
 *     wsQueryKey: 'routes',   // invalidate when WS event has queryKey === 'routes'
 *   })
 */
import { useQueryClient, useQuery, type UseQueryOptions } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useWsStore } from '../store/wsStore'

type RealtimeQueryOptions<T> = UseQueryOptions<T> & {
  /**
   * The queryKey value emitted by the WS server that should trigger invalidation.
   * Maps to `WsMessage.queryKey` from the server.
   */
  wsQueryKey?: string | string[]
}

export function useRealtimeQuery<T>(options: RealtimeQueryOptions<T>) {
  const qc = useQueryClient()
  const { wsQueryKey, ...queryOptions } = options
  const result = useQuery(queryOptions)

  // Subscribe to the ws event stream
  const recentEvents = useWsStore(s => s.recentEvents)

  useEffect(() => {
    if (!wsQueryKey || recentEvents.length === 0) return

    const keys = Array.isArray(wsQueryKey) ? wsQueryKey : [wsQueryKey]
    const latest = recentEvents[0]

    // Find if the most recent event targets this query's key
    const shouldInvalidate = keys.some(k =>
      latest.type.includes(k) ||
      // Check by queryKey string emitted from the server
      k === latest.type.split('.')[0]
    )

    if (shouldInvalidate) {
      qc.invalidateQueries({ queryKey: Array.isArray(options.queryKey) ? options.queryKey : [options.queryKey] })
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recentEvents])

  return result
}

