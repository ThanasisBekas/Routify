import { useQuery } from '@tanstack/react-query'
import { aiApi } from '../../api/aiApi'
import type { AiModifierStats } from '../../types'

/**
 * TanStack Query hook for AI Modifier decision statistics per route.
 *
 * @param routeId  UUID of the route to fetch stats for.
 * @param from     Optional ISO-8601 start timestamp.
 * @param to       Optional ISO-8601 end timestamp.
 */
export const useAiModifierStats = (routeId: string, from?: string, to?: string) =>
  useQuery<AiModifierStats>({
    queryKey: ['ai-modifier-stats', routeId, from, to],
    queryFn: () => aiApi.getAiModifierStats(routeId, from, to),
    refetchInterval: 30_000,
    enabled: !!routeId,
  })

/**
 * TanStack Query hook for paginated AI Modifier decision log per route.
 */
export const useAiModifierDecisions = (
  routeId: string,
  params?: { page?: number; size?: number },
) =>
  useQuery({
    queryKey: ['ai-modifier-decisions', routeId, params],
    queryFn: () => aiApi.listAiModifierDecisions(routeId, params),
    refetchInterval: 30_000,
    enabled: !!routeId,
  })

