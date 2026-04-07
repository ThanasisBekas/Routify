import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { gitopsApi } from '../../api/gitopsApi'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import ReconciliationHistoryTable from './ReconciliationHistoryTable'
import { toast } from 'sonner'
import { extractApiError, cn } from '../../lib/utils'
import {
  GitBranch,
  RefreshCw,
  Clock,
  CheckCircle,
  XCircle,
  AlertTriangle,
  GitCommit,
  FolderGit2,
  Eye,
} from 'lucide-react'

export default function GitOpsPage() {
  useDocumentTitle('GitOps')
  const queryClient = useQueryClient()

  const {
    data: status,
    isLoading: statusLoading,
    error: statusError,
  } = useQuery({
    queryKey: ['gitops-status'],
    queryFn: gitopsApi.getStatus,
    refetchInterval: 15_000,
  })

  const { data: history = [], isLoading: historyLoading } = useQuery({
    queryKey: ['gitops-history'],
    queryFn: gitopsApi.getHistory,
    refetchInterval: 15_000,
  })

  const syncMutation = useMutation({
    mutationFn: gitopsApi.triggerSync,
    onSuccess: (data) => {
      toast.success(`Sync triggered — outcome: ${data.outcome}`)
      queryClient.invalidateQueries({ queryKey: ['gitops-status'] })
      queryClient.invalidateQueries({ queryKey: ['gitops-history'] })
    },
    onError: (err) => {
      toast.error(extractApiError(err))
    },
  })

  const isConnected = !!status && !statusError
  const lastOutcome = status?.lastOutcome

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* Header */}
      <div className="shrink-0 px-6 py-5 border-b border-white/[0.06]">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-indigo-500/10 border border-indigo-500/20">
              <FolderGit2 className="w-5 h-5 text-indigo-400" />
            </div>
            <div>
              <h1 className="text-lg font-bold text-white">GitOps</h1>
              <p className="text-xs text-gray-500 mt-0.5">Git-based configuration reconciliation</p>
            </div>
          </div>

          <button
            onClick={() => syncMutation.mutate()}
            disabled={syncMutation.isPending}
            className={cn(
              'flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all',
              'bg-indigo-500/10 text-indigo-300 border border-indigo-500/20',
              'hover:bg-indigo-500/20 disabled:opacity-50',
            )}
          >
            <RefreshCw className={cn('w-4 h-4', syncMutation.isPending && 'animate-spin')} />
            {syncMutation.isPending ? 'Syncing…' : 'Sync Now'}
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-6 space-y-6">
        {statusLoading ? (
          <div className="flex items-center justify-center py-20">
            <div className="w-6 h-6 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
          </div>
        ) : statusError ? (
          <div className="rounded-xl border border-red-500/20 bg-red-500/[0.05] p-6 text-center">
            <XCircle className="w-8 h-8 text-red-400 mx-auto mb-3" />
            <p className="text-sm text-red-300 font-medium">GitOps agent is not reachable</p>
            <p className="text-xs text-gray-500 mt-1">
              The agent may not be running or the admin-api proxy is not configured.
            </p>
          </div>
        ) : (
          <>
            {/* Connection status + Drift detection banner */}
            {status?.dryRun && lastOutcome === 'DRIFT_DETECTED' && (
              <div className="rounded-xl border border-amber-500/20 bg-amber-500/[0.05] p-4 flex items-start gap-3">
                <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
                <div>
                  <p className="text-sm font-medium text-amber-300">Configuration drift detected</p>
                  <p className="text-xs text-gray-400 mt-1">
                    The agent is running in dry-run mode. Changes were detected in Git but not applied.
                    Disable dry-run mode or manually import the configuration.
                  </p>
                </div>
              </div>
            )}

            {/* Status cards */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
              {/* Connection */}
              <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-4">
                <div className="flex items-center gap-2 text-xs text-gray-500 mb-2">
                  <span
                    className={cn(
                      'w-2 h-2 rounded-full',
                      isConnected ? 'bg-emerald-400 animate-pulse' : 'bg-red-500',
                    )}
                  />
                  Agent Status
                </div>
                <div className={cn('text-sm font-medium', isConnected ? 'text-emerald-400' : 'text-red-400')}>
                  {isConnected ? (status?.enabled ? 'Connected' : 'Disabled') : 'Disconnected'}
                </div>
              </div>

              {/* Last Sync */}
              <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-4">
                <div className="flex items-center gap-2 text-xs text-gray-500 mb-2">
                  <Clock className="w-3.5 h-3.5" />
                  Last Sync
                </div>
                <div className="text-sm font-medium text-gray-300">
                  {status?.lastSyncTime ? new Date(status.lastSyncTime).toLocaleString() : 'Never'}
                </div>
              </div>

              {/* Last Outcome */}
              <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-4">
                <div className="flex items-center gap-2 text-xs text-gray-500 mb-2">
                  {lastOutcome === 'APPLIED' && <CheckCircle className="w-3.5 h-3.5 text-emerald-400" />}
                  {lastOutcome === 'FAILED' && <XCircle className="w-3.5 h-3.5 text-red-400" />}
                  {lastOutcome === 'DRIFT_DETECTED' && <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />}
                  {(!lastOutcome || lastOutcome === 'NO_CHANGE') && <CheckCircle className="w-3.5 h-3.5" />}
                  Last Outcome
                </div>
                <div
                  className={cn(
                    'text-sm font-medium',
                    lastOutcome === 'APPLIED' && 'text-emerald-400',
                    lastOutcome === 'FAILED' && 'text-red-400',
                    lastOutcome === 'DRIFT_DETECTED' && 'text-amber-400',
                    (!lastOutcome || lastOutcome === 'NO_CHANGE') && 'text-gray-400',
                  )}
                >
                  {lastOutcome?.replace('_', ' ') ?? 'N/A'}
                </div>
              </div>

              {/* Mode */}
              <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-4">
                <div className="flex items-center gap-2 text-xs text-gray-500 mb-2">
                  <Eye className="w-3.5 h-3.5" />
                  Mode
                </div>
                <div
                  className={cn(
                    'text-sm font-medium',
                    status?.dryRun ? 'text-amber-400' : 'text-indigo-400',
                  )}
                >
                  {status?.dryRun ? 'Dry Run (observe only)' : 'Active (auto-apply)'}
                </div>
              </div>
            </div>

            {/* Repository info */}
            <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-5">
              <h3 className="text-sm font-semibold text-white mb-4">Repository Configuration</h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-sm">
                <div className="flex items-center gap-3">
                  <FolderGit2 className="w-4 h-4 text-gray-500 shrink-0" />
                  <div>
                    <div className="text-[10px] text-gray-600 uppercase tracking-wider">Repository</div>
                    <div className="text-gray-300 font-mono text-xs break-all">
                      {status?.repositoryUrl || '—'}
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <GitBranch className="w-4 h-4 text-gray-500 shrink-0" />
                  <div>
                    <div className="text-[10px] text-gray-600 uppercase tracking-wider">Branch</div>
                    <div className="text-gray-300">{status?.branch || '—'}</div>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <GitCommit className="w-4 h-4 text-gray-500 shrink-0" />
                  <div>
                    <div className="text-[10px] text-gray-600 uppercase tracking-wider">Last Commit</div>
                    <div className="text-indigo-400 font-mono text-xs">
                      {status?.lastCommitHash?.substring(0, 12) || '—'}
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <Clock className="w-4 h-4 text-gray-500 shrink-0" />
                  <div>
                    <div className="text-[10px] text-gray-600 uppercase tracking-wider">Poll Interval</div>
                    <div className="text-gray-300">{status?.pollIntervalSeconds ?? '—'}s</div>
                  </div>
                </div>
              </div>
            </div>

            {/* Reconciliation history */}
            <div className="rounded-xl border border-white/[0.06] bg-white/[0.02] overflow-hidden">
              <div className="px-5 py-4 border-b border-white/[0.06]">
                <h3 className="text-sm font-semibold text-white">Reconciliation History</h3>
                <p className="text-xs text-gray-500 mt-0.5">Last 50 reconciliation cycles</p>
              </div>
              {historyLoading ? (
                <div className="flex items-center justify-center py-12">
                  <div className="w-5 h-5 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
                </div>
              ) : (
                <ReconciliationHistoryTable history={history} />
              )}
            </div>
          </>
        )}
      </div>
    </div>
  )
}

