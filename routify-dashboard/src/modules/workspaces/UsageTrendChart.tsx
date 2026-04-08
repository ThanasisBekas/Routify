import { useQuery } from '@tanstack/react-query'
import { tenantsApi } from '../../api/tenantsApi'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { Loader2, AlertCircle, TrendingUp } from 'lucide-react'

interface UsageTrendChartProps {
  tenantId: string
  days?: number
}

export default function UsageTrendChart({ tenantId, days = 30 }: UsageTrendChartProps) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['tenant-usage-history', tenantId, days],
    queryFn: () => tenantsApi.getUsageHistory(tenantId, days),
    staleTime: 60_000,
    enabled: !!tenantId,
  })

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-gray-500 text-sm py-8 justify-center">
        <Loader2 className="w-4 h-4 animate-spin" />
        Loading trend data…
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="flex items-center gap-2 text-gray-500 text-sm py-8 justify-center">
        <AlertCircle className="w-4 h-4" />
        Unable to load trend data.
      </div>
    )
  }

  const entries = [...data.entries].reverse() // oldest first for chart

  if (entries.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-8 text-gray-500 gap-2">
        <TrendingUp className="w-6 h-6 opacity-40" />
        <p className="text-sm">No usage history yet.</p>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider flex items-center gap-1.5">
        <TrendingUp className="w-3.5 h-3.5" />
        Daily Request Volume — Last {days} Days
      </h3>

      <div className="h-48">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={entries} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" />
            <XAxis
              dataKey="date"
              tick={{ fontSize: 10, fill: '#6b7280' }}
              tickFormatter={(d: string) => d.slice(5)} // "MM-DD"
            />
            <YAxis tick={{ fontSize: 10, fill: '#6b7280' }} width={50} />
            <Tooltip
              contentStyle={{
                backgroundColor: '#1a1d23',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 12,
                fontSize: 12,
              }}
              labelStyle={{ color: '#9ca3af' }}
            />
            <Line
              type="monotone"
              dataKey="requestCount"
              name="Requests"
              stroke="#6366f1"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, strokeWidth: 0 }}
            />
            <Line
              type="monotone"
              dataKey="errorCount"
              name="Errors"
              stroke="#ef4444"
              strokeWidth={1.5}
              dot={false}
              activeDot={{ r: 3, strokeWidth: 0 }}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
