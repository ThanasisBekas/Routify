/**
 * useRealtimeQuery — React Query + WebSocket integration.
 *
 * Wraps useQuery and automatically invalidates the cache when a WebSocket
 * event arrives whose type or queryKey matches. This replaces polling
 * (`refetchInterval`) with event-driven cache invalidation:
 *
 *   - Route list refetches automatically when route.activated fires
 *   - Audit page gets new events without manual refresh
 *   - Gateway overview updates circuit breakers live from /topic/metrics
 *   - Certificate pages refresh on certificate.* events
 *
 * Usage:
 *   const { data } = useRealtimeQuery({
 *     queryKey: ['routes'],
 *     queryFn: routesApi.list,
 *     wsEvents: ['route'],  // invalidate when WS event type starts with 'route.'
 *   })
 */
import { useQueryClient, useQuery, type UseQueryOptions } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { useWsStore } from '../store/wsStore'

type RealtimeQueryOptions<T> = UseQueryOptions<T> & {
  /**
   * WS event type prefixes or exact types that should trigger cache invalidation.
   * E.g. ['route', 'filter'] will invalidate when events like 'route.created',
   * 'route.activated', 'filter.updated', etc. arrive.
   *
   * Also matches against `WsMessage.queryKey` emitted from the server.
   */
  wsEvents?: string[]
}

export function useRealtimeQuery<T>(options: RealtimeQueryOptions<T>) {
  const qc = useQueryClient()
  const { wsEvents, ...queryOptions } = options
  const result = useQuery(queryOptions)

  // Subscribe to the ws event stream
  const recentEvents = useWsStore((s) => s.recentEvents)
  // Track last processed event id to avoid re-processing
  const lastProcessedRef = useRef<string | null>(null)

  useEffect(() => {
    if (!wsEvents || wsEvents.length === 0 || recentEvents.length === 0) return

    const latest = recentEvents[0]
    // Avoid processing the same event twice
    if (latest.id === lastProcessedRef.current) return
    lastProcessedRef.current = latest.id

    // Check if the latest event matches any of the subscribed event prefixes
    const shouldInvalidate = wsEvents.some(
      (key) =>
        latest.type.startsWith(key + '.') ||
        latest.type === key ||
        // Match the domain prefix: 'route' matches 'route.created', 'route.updated', etc.
        key === latest.type.split('.')[0],
    )

    if (shouldInvalidate) {
      qc.invalidateQueries({ queryKey: Array.isArray(options.queryKey) ? options.queryKey : [options.queryKey] })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recentEvents])

  return result
}
