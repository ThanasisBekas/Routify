/**
 * VersionComparisonPage — Side-by-side comparison of two AI prompt versions.
 *
 * Charts: accuracy trend, block rate, average latency, confidence distribution.
 * "Promote" button activates the better-performing version.
 */
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts'
import { toast } from 'sonner'
import { aiApi } from '../../api/aiApi'
import { extractApiError, cn } from '../../lib/utils'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import type { AiPromptVersionSummary } from '../../types'

export default function VersionComparisonPage() {
  useDocumentTitle('Version Comparison')
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()

  const filterId = searchParams.get('filterId') ?? ''
  const [versionA, setVersionA] = useState<string>('')
  const [versionB, setVersionB] = useState<string>('')

  // Fetch versions for the filter
  const { data: versionsPage } = useQuery({
    queryKey: ['prompt-versions', filterId],
    queryFn: () => aiApi.listPromptVersions(filterId, 0, 50),
    enabled: !!filterId,
  })
  const versions: AiPromptVersionSummary[] = versionsPage?.content ?? []

  const versionAInfo = versions.find((v) => v.id === versionA)
  const versionBInfo = versions.find((v) => v.id === versionB)

  // Build comparison data from version summaries
  const comparisonData =
    versionAInfo && versionBInfo
      ? [
          {
            metric: 'Accuracy',
            [`v${versionAInfo.version}`]: versionAInfo.accuracyScore ?? 0,
            [`v${versionBInfo.version}`]: versionBInfo.accuracyScore ?? 0,
          },
          {
            metric: 'Decisions',
            [`v${versionAInfo.version}`]: versionAInfo.totalDecisions,
            [`v${versionBInfo.version}`]: versionBInfo.totalDecisions,
          },
        ]
      : []

  const activateMutation = useMutation({
    mutationFn: (versionId: string) => aiApi.activateVersion(filterId, versionId),
    onSuccess: (data) => {
      toast.success(`v${data.version} promoted to ACTIVE`)
      queryClient.invalidateQueries({ queryKey: ['prompt-versions', filterId] })
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  if (!filterId) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-gray-500 text-sm">
          Navigate here from the AI Playground with a filter selected.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full overflow-y-auto px-6 py-5 gap-6">
      {/* Header */}
      <div>
        <h1 className="text-lg font-bold text-white">Prompt Version Comparison</h1>
        <p className="text-xs text-gray-500 mt-0.5">Compare accuracy, latency, and block rates between versions</p>
      </div>

      {/* Version selectors */}
      <div className="flex gap-4">
        <div className="flex-1">
          <label className="text-[11px] text-gray-500 block mb-1">Version A</label>
          <select
            value={versionA}
            onChange={(e) => setVersionA(e.target.value)}
            className="w-full rounded-lg border border-white/[0.08] bg-[#0d0f14] px-3 py-2 text-sm text-gray-200"
          >
            <option value="">Select…</option>
            {versions.map((v) => (
              <option key={v.id} value={v.id}>
                v{v.version} ({v.status})
              </option>
            ))}
          </select>
        </div>
        <div className="flex-1">
          <label className="text-[11px] text-gray-500 block mb-1">Version B</label>
          <select
            value={versionB}
            onChange={(e) => setVersionB(e.target.value)}
            className="w-full rounded-lg border border-white/[0.08] bg-[#0d0f14] px-3 py-2 text-sm text-gray-200"
          >
            <option value="">Select…</option>
            {versions.map((v) => (
              <option key={v.id} value={v.id}>
                v{v.version} ({v.status})
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Comparison chart */}
      {comparisonData.length > 0 && versionAInfo && versionBInfo && (
        <>
          <div className="rounded-xl bg-white/[0.03] border border-white/[0.06] p-5">
            <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-4">
              Side-by-Side Comparison
            </h3>
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={comparisonData}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
                <XAxis dataKey="metric" tick={{ fontSize: 12, fill: '#9ca3af' }} />
                <YAxis tick={{ fontSize: 11, fill: '#9ca3af' }} />
                <Tooltip />
                <Legend />
                <Bar
                  dataKey={`v${versionAInfo.version}`}
                  fill="#6366f1"
                  radius={[4, 4, 0, 0]}
                  name={`v${versionAInfo.version}`}
                />
                <Bar
                  dataKey={`v${versionBInfo.version}`}
                  fill="#a78bfa"
                  radius={[4, 4, 0, 0]}
                  name={`v${versionBInfo.version}`}
                />
              </BarChart>
            </ResponsiveContainer>
          </div>

          {/* Summary cards */}
          <div className="grid grid-cols-2 gap-4">
            {[
              { info: versionAInfo, label: 'A' },
              { info: versionBInfo, label: 'B' },
            ].map(({ info, label }) => (
              <div
                key={label}
                className="rounded-xl bg-white/[0.03] border border-white/[0.06] p-4 space-y-2"
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm font-bold text-white">
                    v{info.version}
                  </span>
                  <span
                    className={cn(
                      'px-1.5 py-0.5 text-[10px] font-bold rounded border',
                      info.status === 'ACTIVE'
                        ? 'text-emerald-300 bg-emerald-500/10 border-emerald-500/20'
                        : info.status === 'DRAFT'
                          ? 'text-blue-300 bg-blue-500/10 border-blue-500/20'
                          : 'text-gray-400 bg-white/[0.03] border-white/[0.06]',
                    )}
                  >
                    {info.status}
                  </span>
                </div>
                <div className="text-xs text-gray-500">
                  Accuracy: <span className="text-white">{info.accuracyScore?.toFixed(1) ?? 'N/A'}%</span>
                </div>
                <div className="text-xs text-gray-500">
                  Decisions: <span className="text-white">{info.totalDecisions}</span>
                </div>
                {info.status !== 'ACTIVE' && info.status !== 'ARCHIVED' && (
                  <button
                    onClick={() => activateMutation.mutate(info.id)}
                    disabled={activateMutation.isPending}
                    className="mt-2 w-full py-1.5 text-xs font-semibold rounded-lg bg-emerald-600/80 text-white hover:bg-emerald-500 transition-colors disabled:opacity-50"
                  >
                    Promote to ACTIVE
                  </button>
                )}
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

