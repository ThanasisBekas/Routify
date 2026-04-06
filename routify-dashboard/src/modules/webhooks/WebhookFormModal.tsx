import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { toast } from 'sonner'
import { X } from 'lucide-react'
import { webhooksApi } from '../../api/webhooksApi'
import { extractApiError } from '../../lib/utils'
import type { WebhookEventType } from '../../types'


const EVENT_GROUPS = {
  Routes: ['ROUTE_CREATED', 'ROUTE_ACTIVATED', 'ROUTE_DEACTIVATED', 'ROUTE_DELETED', 'ROUTE_PROMOTED'],
  Filters: ['FILTER_CREATED', 'FILTER_UPDATED', 'FILTER_DELETED'],
  Certificates: ['CERT_UPLOADED', 'CERT_REVOKED', 'CERT_EXPIRING', 'CERT_EXPIRED'],
  Users: ['USER_CREATED', 'USER_DELETED'],
  Tenants: ['TENANT_SUSPENDED', 'TENANT_REACTIVATED'],
  AI: ['AI_FILTER_BLOCKED', 'AI_FILTER_FLAGGED'],
  Infrastructure: ['DLQ_OVERFLOW', 'GATEWAY_RELOAD_FAILED'],
} as const

const schema = z.object({
  name: z.string().min(1, 'Name is required'),
  url: z.string().url('Must be a valid URL'),
  eventTypes: z.array(z.string()).min(1, 'Select at least one event type'),
})

type FormValues = z.infer<typeof schema>

interface Props {
  onClose: () => void
}

export default function WebhookFormModal({ onClose }: Props) {
  const qc = useQueryClient()
  const { register, handleSubmit, watch, setValue, formState: { errors } } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', url: '', eventTypes: [] },
  })

  const selectedEvents = watch('eventTypes')

  const createMut = useMutation({
    mutationFn: (data: FormValues) =>
      webhooksApi.create({
        name: data.name,
        url: data.url,
        eventTypes: data.eventTypes as WebhookEventType[],
      }),
    onSuccess: () => {
      toast.success('Webhook subscription created')
      qc.invalidateQueries({ queryKey: ['webhooks'] })
      onClose()
    },
    onError: (e) => toast.error(extractApiError(e)),
  })

  const toggleEvent = (eventType: string) => {
    const current = selectedEvents || []
    if (current.includes(eventType)) {
      setValue('eventTypes', current.filter((e) => e !== eventType), { shouldValidate: true })
    } else {
      setValue('eventTypes', [...current, eventType], { shouldValidate: true })
    }
  }

  const toggleGroup = (events: readonly string[]) => {
    const current = selectedEvents || []
    const allSelected = events.every((e) => current.includes(e))
    if (allSelected) {
      setValue('eventTypes', current.filter((e) => !events.includes(e)), { shouldValidate: true })
    } else {
      const merged = [...new Set([...current, ...events])]
      setValue('eventTypes', merged, { shouldValidate: true })
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="bg-[#0d0f14] border border-white/10 rounded-xl w-full max-w-lg max-h-[90vh] overflow-y-auto shadow-2xl">
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/[0.06]">
          <h2 className="text-base font-bold text-white">Create Webhook Subscription</h2>
          <button onClick={onClose} className="p-1 rounded hover:bg-white/[0.05] text-gray-500">
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit((data) => createMut.mutate(data))} className="p-5 space-y-4">
          {/* Name */}
          <div>
            <label className="block text-xs font-medium text-gray-400 mb-1">Name</label>
            <input
              {...register('name')}
              className="w-full px-3 py-2 rounded-lg bg-white/[0.04] border border-white/[0.08] text-white text-sm placeholder-gray-600 focus:outline-none focus:border-indigo-500/50"
              placeholder="e.g., Slack Notifications"
            />
            {errors.name && <p className="mt-1 text-xs text-red-400">{errors.name.message}</p>}
          </div>

          {/* URL */}
          <div>
            <label className="block text-xs font-medium text-gray-400 mb-1">Webhook URL</label>
            <input
              {...register('url')}
              className="w-full px-3 py-2 rounded-lg bg-white/[0.04] border border-white/[0.08] text-white text-sm placeholder-gray-600 focus:outline-none focus:border-indigo-500/50 font-mono"
              placeholder="https://hooks.example.com/routify"
            />
            {errors.url && <p className="mt-1 text-xs text-red-400">{errors.url.message}</p>}
          </div>

          {/* Event Types */}
          <div>
            <label className="block text-xs font-medium text-gray-400 mb-2">Event Types</label>
            {errors.eventTypes && (
              <p className="mb-2 text-xs text-red-400">{errors.eventTypes.message}</p>
            )}
            <div className="space-y-3">
              {Object.entries(EVENT_GROUPS).map(([group, events]) => (
                <div key={group}>
                  <button
                    type="button"
                    onClick={() => toggleGroup(events)}
                    className="text-[11px] font-semibold text-gray-500 uppercase tracking-wider hover:text-gray-300 transition-colors mb-1"
                  >
                    {group}
                  </button>
                  <div className="flex flex-wrap gap-1.5">
                    {events.map((et) => {
                      const isSelected = (selectedEvents || []).includes(et)
                      return (
                        <button
                          key={et}
                          type="button"
                          onClick={() => toggleEvent(et)}
                          className={`px-2 py-1 rounded text-[11px] font-medium border transition-colors ${
                            isSelected
                              ? 'bg-indigo-500/15 text-indigo-300 border-indigo-500/30'
                              : 'bg-white/[0.03] text-gray-500 border-white/[0.06] hover:bg-white/[0.06]'
                          }`}
                        >
                          {et}
                        </button>
                      )
                    })}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <p className="text-[11px] text-gray-600">
            A unique signing secret will be auto-generated. You'll see it once after creation.
          </p>

          {/* Actions */}
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg text-sm text-gray-400 hover:text-white hover:bg-white/[0.05] transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={createMut.isPending}
              className="px-4 py-2 rounded-lg text-sm font-medium bg-indigo-500/20 text-indigo-300 border border-indigo-500/30 hover:bg-indigo-500/30 transition-colors disabled:opacity-50"
            >
              {createMut.isPending ? 'Creating…' : 'Create Webhook'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

