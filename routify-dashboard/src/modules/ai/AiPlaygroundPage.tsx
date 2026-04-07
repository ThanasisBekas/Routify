/**
 * AiPlaygroundPage — Interactive AI Policy Playground with prompt versioning.
 *
 * Split-pane layout:
 *  Left: Prompt editor + version history
 *  Right: Test request builder + verdict display
 */
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { aiApi } from '../../api/aiApi'
import { cn, extractApiError } from '../../lib/utils'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import PromptEditor from './components/PromptEditor'
import TestRequestBuilder from './components/TestRequestBuilder'
import VerdictDisplay from './components/VerdictDisplay'
import type { AiPromptVersionSummary, FilterSummary } from '../../types'
import apiClient from '../../api/client'

type Verdict = { action: string; reason: string; confidence: number; latencyMs?: number; cached?: boolean }

export default function AiPlaygroundPage() {
  useDocumentTitle('AI Playground')

  const queryClient = useQueryClient()

  // ─── Filter selection ───────────────────────────────────────────────────
  const [selectedFilterId, setSelectedFilterId] = useState<string>('')
  const [promptText, setPromptText] = useState('')
  const [description, setDescription] = useState('')
  const [verdict, setVerdict] = useState<Verdict | null>(null)

  // Fetch AI_FILTER filters
  const { data: filtersPage } = useQuery({
    queryKey: ['ai-filters'],
    queryFn: () =>
      apiClient
        .get<{ content: FilterSummary[] }>('/api/v1/admin/filters', { params: { page: 0, size: 100 } })
        .then((r) => r.data),
    staleTime: 60_000,
  })

  const aiFilters = (filtersPage?.content ?? []).filter((f) => f.filterType === 'AI_FILTER')

  // ─── Prompt versions ────────────────────────────────────────────────────
  const { data: versionsPage } = useQuery({
    queryKey: ['prompt-versions', selectedFilterId],
    queryFn: () => aiApi.listPromptVersions(selectedFilterId, 0, 50),
    enabled: !!selectedFilterId,
    staleTime: 10_000,
  })

  const versions: AiPromptVersionSummary[] = versionsPage?.content ?? []

  // ─── Test policy mutation ───────────────────────────────────────────────
  const testMutation = useMutation({
    mutationFn: (req: { method: string; path: string; headers?: Record<string, string>; bodyExcerpt?: string }) =>
      aiApi.testPolicy({
        policyDescription: promptText,
        promptOverride: promptText,
        sampleRequest: { method: req.method, path: req.path, headers: req.headers, body: req.bodyExcerpt },
      }),
    onSuccess: (data) => setVerdict(data),
    onError: (err) => toast.error(extractApiError(err)),
  })

  // ─── Create draft mutation ──────────────────────────────────────────────
  const createDraftMutation = useMutation({
    mutationFn: () => aiApi.createDraftVersion(selectedFilterId, { promptText, description: description || undefined }),
    onSuccess: (data) => {
      toast.success(`Draft v${data.version} created`)
      queryClient.invalidateQueries({ queryKey: ['prompt-versions', selectedFilterId] })
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  // ─── Activate version mutation ──────────────────────────────────────────
  const activateMutation = useMutation({
    mutationFn: (versionId: string) => aiApi.activateVersion(selectedFilterId, versionId),
    onSuccess: (data) => {
      toast.success(`v${data.version} activated`)
      queryClient.invalidateQueries({ queryKey: ['prompt-versions', selectedFilterId] })
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  // ─── Archive version mutation ───────────────────────────────────────────
  const archiveMutation = useMutation({
    mutationFn: (versionId: string) => aiApi.archiveVersion(selectedFilterId, versionId),
    onSuccess: () => {
      toast.success('Version archived')
      queryClient.invalidateQueries({ queryKey: ['prompt-versions', selectedFilterId] })
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  // ─── Load version into editor ──────────────────────────────────────────
  const loadVersion = async (v: AiPromptVersionSummary) => {
    try {
      const detail = await aiApi.getPromptVersion(selectedFilterId, v.id)
      setPromptText(detail.promptText)
      setDescription(detail.description ?? '')
    } catch {
      toast.error('Failed to load version')
    }
  }

  return (
    <div className="flex flex-col h-full overflow-hidden">
      {/* Header */}
      <div className="shrink-0 px-6 py-4 border-b border-white/[0.06] flex items-center justify-between">
        <div>
          <h1 className="text-lg font-bold text-white">AI Policy Playground</h1>
          <p className="text-xs text-gray-500 mt-0.5">Test & version AI filter prompts</p>
        </div>

        {/* Filter selector */}
        <select
          value={selectedFilterId}
          onChange={(e) => {
            setSelectedFilterId(e.target.value)
            setPromptText('')
            setDescription('')
            setVerdict(null)
          }}
          className="rounded-lg border border-white/[0.08] bg-[#0d0f14] px-3 py-2 text-sm text-gray-200 focus:outline-none focus:ring-1 focus:ring-indigo-500/50 min-w-[200px]"
        >
          <option value="">Select AI Filter…</option>
          {aiFilters.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {/* Split pane */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left: Prompt editor + version history */}
        <div className="w-1/2 border-r border-white/[0.06] flex flex-col overflow-y-auto p-5 gap-5">
          <PromptEditor value={promptText} onChange={setPromptText} disabled={!selectedFilterId} />

          {/* Description */}
          <div>
            <label className="text-[11px] text-gray-500">Description (optional)</label>
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="What changed in this version..."
              disabled={!selectedFilterId}
              className="w-full mt-1 rounded-lg border border-white/[0.08] bg-[#0d0f14] px-3 py-2 text-sm text-gray-300 placeholder:text-gray-600 focus:outline-none focus:ring-1 focus:ring-indigo-500/50 disabled:opacity-50"
            />
          </div>

          {/* Actions */}
          <div className="flex gap-2">
            <button
              onClick={() => createDraftMutation.mutate()}
              disabled={!selectedFilterId || !promptText.trim() || createDraftMutation.isPending}
              className="px-4 py-2 text-sm font-medium rounded-lg border border-white/[0.08] text-gray-300 hover:text-white hover:bg-white/[0.04] disabled:opacity-40 transition-all"
            >
              Save Draft
            </button>
          </div>

          {/* Version history */}
          {selectedFilterId && versions.length > 0 && (
            <div className="space-y-2">
              <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Version History</h3>
              <div className="space-y-1">
                {versions.map((v) => (
                  <div
                    key={v.id}
                    className="flex items-center gap-2 px-3 py-2 rounded-lg border border-white/[0.04] hover:bg-white/[0.02] transition-colors group"
                  >
                    <span className="font-mono text-sm text-gray-300">v{v.version}</span>
                    <span
                      className={cn(
                        'px-1.5 py-0.5 text-[10px] font-bold rounded border',
                        v.status === 'ACTIVE'
                          ? 'text-emerald-300 bg-emerald-500/10 border-emerald-500/20'
                          : v.status === 'DRAFT'
                            ? 'text-blue-300 bg-blue-500/10 border-blue-500/20'
                            : 'text-gray-400 bg-white/[0.03] border-white/[0.06]',
                      )}
                    >
                      {v.status}
                    </span>
                    {v.accuracyScore != null && (
                      <span className="text-[10px] text-indigo-400">{v.accuracyScore.toFixed(1)}% acc</span>
                    )}
                    <span className="text-[10px] text-gray-600 ml-auto">
                      {new Date(v.createdAt).toLocaleDateString()}
                    </span>

                    {/* Actions */}
                    <div className="hidden group-hover:flex gap-1">
                      <button
                        onClick={() => loadVersion(v)}
                        className="text-[10px] text-indigo-400 hover:text-indigo-300"
                      >
                        Load
                      </button>
                      {v.status === 'DRAFT' && (
                        <button
                          onClick={() => activateMutation.mutate(v.id)}
                          className="text-[10px] text-emerald-400 hover:text-emerald-300"
                        >
                          Activate
                        </button>
                      )}
                      {v.status !== 'ARCHIVED' && (
                        <button
                          onClick={() => archiveMutation.mutate(v.id)}
                          className="text-[10px] text-gray-400 hover:text-gray-300"
                        >
                          Archive
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Right: Test request + verdict */}
        <div className="w-1/2 flex flex-col overflow-y-auto p-5 gap-5">
          <TestRequestBuilder
            onRun={(req) => testMutation.mutate(req)}
            isRunning={testMutation.isPending}
          />

          <div className="border-t border-white/[0.06] pt-4">
            <VerdictDisplay verdict={verdict} isLoading={testMutation.isPending} />
          </div>
        </div>
      </div>
    </div>
  )
}

