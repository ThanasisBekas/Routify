import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { toast } from 'sonner'
import { alertsApi } from '../../api/alertsApi'
import { extractApiError } from '../../lib/utils'
import type {
  AlertRule,
  AlertMetric,
  AlertOperator,
  AlertSeverity,
  CreateAlertRuleRequest,
  UpdateAlertRuleRequest,
} from '../../types'

const METRICS: { value: AlertMetric; label: string; unit: string }[] = [
  { value: 'ERROR_RATE', label: 'Error Rate', unit: '%' },
  { value: 'P99_LATENCY', label: 'P99 Latency', unit: 'ms' },
  { value: 'DLQ_DEPTH', label: 'DLQ Depth', unit: 'events' },
  { value: 'CERT_EXPIRY_DAYS', label: 'Cert Expiry', unit: 'days' },
  { value: 'QUOTA_USAGE', label: 'Quota Usage', unit: 'requests' },
  { value: 'SLO_BUDGET', label: 'SLO Budget', unit: '%' },
  { value: 'REQUEST_VOLUME', label: 'Request Volume', unit: 'req/min' },
  { value: 'AUTH_FAILURE_RATE', label: 'Auth Failure Rate', unit: '%' },
]

const OPERATORS: { value: AlertOperator; label: string }[] = [
  { value: 'GT', label: '>' },
  { value: 'GTE', label: '≥' },
  { value: 'LT', label: '<' },
  { value: 'LTE', label: '≤' },
  { value: 'EQ', label: '=' },
]

const SEVERITIES: AlertSeverity[] = ['INFO', 'WARNING', 'CRITICAL']

interface Props {
  rule: AlertRule | null
  onClose: () => void
}

export default function AlertRuleFormModal({ rule, onClose }: Props) {
  const isEdit = rule?.id ? true : false
  const queryClient = useQueryClient()

  const [name, setName] = useState(rule?.name ?? '')
  const [description, setDescription] = useState(rule?.description ?? '')
  const [metric, setMetric] = useState<AlertMetric>(rule?.metric ?? 'ERROR_RATE')
  const [operator, setOperator] = useState<AlertOperator>(rule?.operator ?? 'GT')
  const [threshold, setThreshold] = useState(rule?.threshold?.toString() ?? '')
  const [windowMinutes, setWindowMinutes] = useState(rule?.windowMinutes?.toString() ?? '5')
  const [cooldownMinutes, setCooldownMinutes] = useState(rule?.cooldownMinutes?.toString() ?? '30')
  const [severity, setSeverity] = useState<AlertSeverity>(rule?.severity ?? 'WARNING')
  const [routeId, setRouteId] = useState(rule?.routeId ?? '')

  const createMutation = useMutation({
    mutationFn: (data: CreateAlertRuleRequest) => alertsApi.create(data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['alerts'] })
      toast.success('Alert rule created')
      onClose()
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateAlertRuleRequest }) =>
      alertsApi.update(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['alerts'] })
      toast.success('Alert rule updated')
      onClose()
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim() || !threshold.trim()) {
      toast.error('Name and threshold are required')
      return
    }

    const data = {
      name: name.trim(),
      description: description.trim() || undefined,
      metric,
      routeId: routeId.trim() || undefined,
      operator,
      threshold: parseFloat(threshold),
      windowMinutes: parseInt(windowMinutes) || 5,
      cooldownMinutes: parseInt(cooldownMinutes) || 30,
      severity,
    }

    if (isEdit && rule?.id) {
      updateMutation.mutate({ id: rule.id, data })
    } else {
      createMutation.mutate(data)
    }
  }

  const pending = createMutation.isPending || updateMutation.isPending

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="w-full max-w-lg mx-4 bg-[#0d0f14] border border-white/10 rounded-2xl shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <h2 className="text-base font-semibold text-white">
            {isEdit ? 'Edit Alert Rule' : 'Create Alert Rule'}
          </h2>
          <button onClick={onClose} className="p-1 text-gray-500 hover:text-white transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="px-6 py-5 space-y-4">
          {/* Name */}
          <div>
            <label className="block text-xs font-medium text-gray-400 mb-1">Name</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-indigo-500"
              placeholder="e.g. Orders API error rate"
            />
          </div>

          {/* Description */}
          <div>
            <label className="block text-xs font-medium text-gray-400 mb-1">Description</label>
            <input
              type="text"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-indigo-500"
              placeholder="Optional description"
            />
          </div>

          {/* Metric + Operator + Threshold row */}
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="block text-xs font-medium text-gray-400 mb-1">Metric</label>
              <select
                value={metric}
                onChange={(e) => setMetric(e.target.value as AlertMetric)}
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white focus:outline-none focus:ring-1 focus:ring-indigo-500"
              >
                {METRICS.map((m) => (
                  <option key={m.value} value={m.value}>
                    {m.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-400 mb-1">Operator</label>
              <select
                value={operator}
                onChange={(e) => setOperator(e.target.value as AlertOperator)}
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white focus:outline-none focus:ring-1 focus:ring-indigo-500"
              >
                {OPERATORS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-400 mb-1">
                Threshold ({METRICS.find((m) => m.value === metric)?.unit ?? ''})
              </label>
              <input
                type="number"
                step="any"
                value={threshold}
                onChange={(e) => setThreshold(e.target.value)}
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-indigo-500"
                placeholder="5"
              />
            </div>
          </div>

          {/* Window + Cooldown + Severity */}
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="block text-xs font-medium text-gray-400 mb-1">Window (min)</label>
              <input
                type="number"
                value={windowMinutes}
                onChange={(e) => setWindowMinutes(e.target.value)}
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white focus:outline-none focus:ring-1 focus:ring-indigo-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-400 mb-1">Cooldown (min)</label>
              <input
                type="number"
                value={cooldownMinutes}
                onChange={(e) => setCooldownMinutes(e.target.value)}
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white focus:outline-none focus:ring-1 focus:ring-indigo-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-400 mb-1">Severity</label>
              <select
                value={severity}
                onChange={(e) => setSeverity(e.target.value as AlertSeverity)}
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white focus:outline-none focus:ring-1 focus:ring-indigo-500"
              >
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Route ID (optional) */}
          <div>
            <label className="block text-xs font-medium text-gray-400 mb-1">
              Route ID <span className="text-gray-600">(optional — leave blank for global)</span>
            </label>
            <input
              type="text"
              value={routeId}
              onChange={(e) => setRouteId(e.target.value)}
              className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-sm text-white placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-indigo-500"
              placeholder="UUID of a specific route"
            />
          </div>
        </form>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-white/[0.06]">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg text-sm text-gray-400 hover:text-white hover:bg-white/[0.06] transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={pending}
            className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium transition-colors disabled:opacity-50"
          >
            {pending ? 'Saving…' : isEdit ? 'Update Rule' : 'Create Rule'}
          </button>
        </div>
      </div>
    </div>
  )
}

