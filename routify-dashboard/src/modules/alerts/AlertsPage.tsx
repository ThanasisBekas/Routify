import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Plus, Bell, BellOff, Trash2, History, AlertTriangle } from 'lucide-react'
import { alertsApi } from '../../api/alertsApi'
import { extractApiError, cn } from '../../lib/utils'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import type { AlertRule, AlertState, AlertSeverity } from '../../types'
import AlertRuleFormModal from './AlertRuleFormModal'
import AlertHistoryTimeline from './AlertHistoryTimeline'

const STATE_COLORS: Record<AlertState, string> = {
  OK: 'bg-green-500',
  PENDING: 'bg-amber-500',
  FIRING: 'bg-red-500',
}

const STATE_LABELS: Record<AlertState, string> = {
  OK: 'OK',
  PENDING: 'Pending',
  FIRING: 'Firing',
}

const SEVERITY_COLORS: Record<AlertSeverity, string> = {
  INFO: 'bg-blue-500/20 text-blue-400 border-blue-500/30',
  WARNING: 'bg-amber-500/20 text-amber-400 border-amber-500/30',
  CRITICAL: 'bg-red-500/20 text-red-400 border-red-500/30',
}

const MUTE_DURATIONS = [
  { label: '15 min', value: 15 },
  { label: '1 hour', value: 60 },
  { label: '4 hours', value: 240 },
  { label: '24 hours', value: 1440 },
]

export default function AlertsPage() {
  useDocumentTitle('Alerts')
  const queryClient = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [editingRule, setEditingRule] = useState<AlertRule | null>(null)
  const [selectedRule, setSelectedRule] = useState<string | null>(null)
  const [muteMenu, setMuteMenu] = useState<string | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['alerts'],
    queryFn: () => alertsApi.list({ page: 0, size: 100 }),
    refetchInterval: 30_000,
  })

  const deleteMutation = useMutation({
    mutationFn: alertsApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['alerts'] })
      toast.success('Alert rule deleted')
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const muteMutation = useMutation({
    mutationFn: ({ id, minutes }: { id: string; minutes: number }) =>
      alertsApi.mute(id, { durationMinutes: minutes }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['alerts'] })
      setMuteMenu(null)
      toast.success('Alert muted')
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const unmuteMutation = useMutation({
    mutationFn: alertsApi.unmute,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['alerts'] })
      toast.success('Alert unmuted')
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const rules = data?.content ?? []
  const isMuted = (rule: AlertRule) =>
    rule.mutedUntil && new Date(rule.mutedUntil) > new Date()

  const handleEdit = (rule: AlertRule) => {
    setEditingRule(rule)
    setShowForm(true)
  }

  const handleCreate = () => {
    setEditingRule(null)
    setShowForm(true)
  }

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* Header */}
      <div className="shrink-0 px-8 pt-8 pb-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold text-white">Alert Rules</h1>
            <p className="text-sm text-gray-500 mt-1">
              Threshold-based alerting on SLOs, error rates, DLQ depth, and cert expiry
            </p>
          </div>
          <button
            onClick={handleCreate}
            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium transition-colors"
          >
            <Plus className="w-4 h-4" />
            New Alert Rule
          </button>
        </div>

        {/* Template buttons */}
        <div className="flex gap-2 mt-4">
          {[
            {
              label: 'SLO Budget Alert',
              metric: 'SLO_BUDGET',
              operator: 'GT',
              threshold: 80,
              window: 10,
              severity: 'CRITICAL',
            },
            {
              label: 'Cert Expiry Alert',
              metric: 'CERT_EXPIRY_DAYS',
              operator: 'LT',
              threshold: 30,
              window: 60,
              severity: 'WARNING',
            },
            {
              label: 'DLQ Depth Alert',
              metric: 'DLQ_DEPTH',
              operator: 'GT',
              threshold: 10,
              window: 5,
              severity: 'CRITICAL',
            },
          ].map((tpl) => (
            <button
              key={tpl.label}
              onClick={() => {
                setEditingRule({
                  id: '',
                  tenantId: '',
                  name: tpl.label,
                  metric: tpl.metric as AlertRule['metric'],
                  operator: tpl.operator as AlertRule['operator'],
                  threshold: tpl.threshold,
                  windowMinutes: tpl.window,
                  cooldownMinutes: 30,
                  severity: tpl.severity as AlertRule['severity'],
                  enabled: true,
                  currentState: 'OK',
                  consecutiveBreaches: 0,
                  createdAt: '',
                } as AlertRule)
                setShowForm(true)
              }}
              className="px-3 py-1.5 rounded-lg bg-white/[0.04] border border-white/10 text-xs text-gray-400 hover:text-white hover:bg-white/[0.08] transition-colors"
            >
              <AlertTriangle className="w-3 h-3 inline mr-1" />
              {tpl.label}
            </button>
          ))}
        </div>
      </div>

      {/* Rule list */}
      <div className="flex-1 overflow-auto px-8 pb-8">
        {isLoading ? (
          <div className="flex items-center justify-center py-20">
            <div className="w-5 h-5 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
          </div>
        ) : rules.length === 0 ? (
          <div className="text-center py-20 text-gray-500 text-sm">
            No alert rules configured yet. Create one to get started.
          </div>
        ) : (
          <div className="space-y-3">
            {rules.map((rule) => (
              <div
                key={rule.id}
                className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-4 hover:bg-white/[0.04] transition-colors"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    {/* State indicator */}
                    <div className={cn('w-2.5 h-2.5 rounded-full', STATE_COLORS[rule.currentState])} />
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-semibold text-white">{rule.name}</span>
                        <span
                          className={cn(
                            'px-2 py-0.5 rounded-full text-[10px] font-medium border',
                            SEVERITY_COLORS[rule.severity],
                          )}
                        >
                          {rule.severity}
                        </span>
                        <span className="text-[10px] text-gray-500">
                          {STATE_LABELS[rule.currentState]}
                        </span>
                        {isMuted(rule) && (
                          <span className="px-2 py-0.5 rounded-full text-[10px] font-medium bg-gray-500/20 text-gray-400 border border-gray-500/30">
                            Muted
                          </span>
                        )}
                      </div>
                      <div className="text-xs text-gray-500 mt-0.5">
                        {rule.metric} {rule.operator} {rule.threshold} · {rule.windowMinutes}m window ·{' '}
                        {rule.cooldownMinutes}m cooldown
                        {rule.description && ` · ${rule.description}`}
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-1.5">
                    <button
                      onClick={() =>
                        setSelectedRule(selectedRule === rule.id ? null : rule.id)
                      }
                      className="p-1.5 rounded-md text-gray-500 hover:text-indigo-400 hover:bg-indigo-500/10 transition-colors"
                      title="History"
                    >
                      <History className="w-3.5 h-3.5" />
                    </button>

                    {isMuted(rule) ? (
                      <button
                        onClick={() => unmuteMutation.mutate(rule.id)}
                        className="p-1.5 rounded-md text-gray-500 hover:text-amber-400 hover:bg-amber-500/10 transition-colors"
                        title="Unmute"
                      >
                        <Bell className="w-3.5 h-3.5" />
                      </button>
                    ) : (
                      <div className="relative">
                        <button
                          onClick={() => setMuteMenu(muteMenu === rule.id ? null : rule.id)}
                          className="p-1.5 rounded-md text-gray-500 hover:text-amber-400 hover:bg-amber-500/10 transition-colors"
                          title="Mute"
                        >
                          <BellOff className="w-3.5 h-3.5" />
                        </button>
                        {muteMenu === rule.id && (
                          <div className="absolute right-0 top-8 z-10 bg-[#12141a] border border-white/10 rounded-lg shadow-xl p-1 min-w-[120px]">
                            {MUTE_DURATIONS.map((d) => (
                              <button
                                key={d.value}
                                onClick={() =>
                                  muteMutation.mutate({ id: rule.id, minutes: d.value })
                                }
                                className="block w-full text-left px-3 py-1.5 text-xs text-gray-300 hover:bg-white/[0.06] rounded-md transition-colors"
                              >
                                {d.label}
                              </button>
                            ))}
                          </div>
                        )}
                      </div>
                    )}

                    <button
                      onClick={() => handleEdit(rule)}
                      className="p-1.5 rounded-md text-gray-500 hover:text-white hover:bg-white/[0.06] transition-colors"
                      title="Edit"
                    >
                      <svg
                        className="w-3.5 h-3.5"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                        />
                      </svg>
                    </button>

                    <button
                      onClick={() => {
                        if (confirm(`Delete alert rule "${rule.name}"?`))
                          deleteMutation.mutate(rule.id)
                      }}
                      className="p-1.5 rounded-md text-gray-500 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                      title="Delete"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>

                {/* History timeline (expandable) */}
                {selectedRule === rule.id && (
                  <div className="mt-4 pt-4 border-t border-white/[0.06]">
                    <AlertHistoryTimeline ruleId={rule.id} />
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Form modal */}
      {showForm && (
        <AlertRuleFormModal
          rule={editingRule}
          onClose={() => {
            setShowForm(false)
            setEditingRule(null)
          }}
        />
      )}
    </div>
  )
}

