import { useQuery } from '@tanstack/react-query'
import { aiApi } from '../../api/aiApi'
import type { AiFilterStats } from '../../types'

/**
 * TanStack Query hook for AI Filter decision statistics per route.
 *
 * @param routeId  UUID of the route to fetch stats for.
 * @param from     Optional ISO-8601 start timestamp.
 * @param to       Optional ISO-8601 end timestamp.
 */
export const useAiFilterStats = (routeId: string, from?: string, to?: string) =>
  useQuery<AiFilterStats>({
    queryKey: ['ai-filter-stats', routeId, from, to],
    queryFn: () => aiApi.getAiFilterStats(routeId, from, to),
    refetchInterval: 30_000,
    enabled: !!routeId,
  })

/**
 * TanStack Query hook for paginated AI Filter decision log per route.
 *
 * @param routeId  UUID of the route to fetch decisions for.
 * @param params   Optional pagination and filter params.
 */
export const useAiFilterDecisions = (routeId: string, params?: { page?: number; size?: number; action?: string }) =>
  useQuery({
    queryKey: ['ai-filter-decisions', routeId, params],
    queryFn: () => aiApi.listAiFilterDecisions(routeId, params),
    refetchInterval: 30_000,
    enabled: !!routeId,
  })
