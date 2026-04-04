import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { X, AlertCircle, Route, Network } from 'lucide-react'
import { routesApi } from '../../api/routesApi'
import type { CreateRouteRequest } from '../../types'
import { cn } from '../../lib/utils'
import { extractApiError } from '../../lib/errorUtils'
import { METHOD_OPTIONS, METHOD_COLORS_MODAL as METHOD_COLORS } from './routeConstants'

const schema = z.object({
  name:        z.string().min(3).max(255),
  description: z.string().max(1000).optional(),
  pathPattern: z.string().min(1).startsWith('/'),
  methods:     z.string().min(1),
  upstreamUri: z.string().regex(/^(https?:\/\/.+|lb:\/\/.+)/, 'Must be http(s):// or lb://'),
  stripPrefix: z.string().optional(),
})

type FormData = z.infer<typeof schema>


const inputCls = "w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all"
const monoInputCls = `${inputCls} font-mono`

export default function RouteCreateModal({
  onClose,
  onCreated,
}: {
  onClose: () => void
  /** Called after successful creation — receives the new route id */
  onCreated?: (routeId: string) => void
}) {
  const navigate = useNavigate()

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { methods: 'GET', pathPattern: '/' },
  })

  const mutation = useMutation({
    mutationFn: (data: CreateRouteRequest) => routesApi.create(data),
    onSuccess: (route) => {
      if (onCreated) onCreated(route.id)
      onClose()
      // Always redirect to the builder on creation
      navigate(`/routes/${route.id}/builder`)
    },
  })

  const selectedMethods = watch('methods').split(',').map(m => m.trim()).filter(Boolean)

  const toggleMethod = (m: string) => {
    if (m === '*') { setValue('methods', '*'); return }
    let current = watch('methods').split(',').map(x => x.trim()).filter(x => x && x !== '*')
    if (current.includes(m)) {
      current = current.filter(x => x !== m)
    } else {
      current.push(m)
    }
    setValue('methods', current.join(',') || 'GET')
  }

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4 animate-fade-in">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-lg shadow-2xl animate-fade-in-up">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center">
              <Route className="w-3.5 h-3.5 text-indigo-400" />
            </div>
            <h2 className="text-sm font-bold text-white">Create New Route</h2>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit((d) => mutation.mutate(d))} className="p-6 space-y-4 max-h-[80vh] overflow-y-auto">
          {/* Name */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Route Name *</label>
            <input {...register('name')} placeholder="e.g. orders-api-v1" className={inputCls} />
            {errors.name && <p className="text-xs text-red-400">{errors.name.message}</p>}
          </div>

          {/* Description */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Description</label>
            <textarea {...register('description')} rows={2} placeholder="Optional description"
              className={`${inputCls} resize-none`} />
          </div>

          {/* Path Pattern */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Path Pattern *</label>
            <input {...register('pathPattern')} placeholder="/api/v1/orders/**" className={monoInputCls} />
            <p className="text-[11px] text-gray-600 pl-0.5">Supports wildcards: /api/** and path vars: /users/{'{id}'}</p>
            {errors.pathPattern && <p className="text-xs text-red-400">{errors.pathPattern.message}</p>}
          </div>

          {/* Methods */}
          <div className="space-y-2">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">HTTP Methods *</label>
            <div className="flex gap-1.5 flex-wrap">
              {METHOD_OPTIONS.map((m) => {
                const active = m === '*' ? selectedMethods.includes('*') : selectedMethods.includes(m)
                return (
                  <button
                    key={m}
                    type="button"
                    onClick={() => toggleMethod(m)}
                    className={cn(
                      'px-3 py-1.5 rounded-lg text-xs font-mono font-bold transition-all border',
                      active
                        ? METHOD_COLORS[m] ?? 'bg-indigo-600 text-white border-indigo-500/50'
                        : 'bg-white/[0.04] text-gray-500 border-white/[0.08] hover:border-white/20 hover:text-gray-300'
                    )}
                  >
                    {m}
                  </button>
                )
              })}
            </div>
          </div>

          {/* Upstream URI */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Upstream URI *</label>
            <input {...register('upstreamUri')}
              placeholder="http://orders-service:8080 or lb://orders-service"
              className={monoInputCls} />
            <p className="text-[11px] text-gray-600 pl-0.5">Use lb:// for load-balanced service discovery</p>
            {errors.upstreamUri && <p className="text-xs text-red-400">{errors.upstreamUri.message}</p>}
          </div>

          {/* Strip Prefix */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Strip Prefix</label>
            <input {...register('stripPrefix')}
              placeholder="/api/v1 (stripped before forwarding)"
              className={monoInputCls} />
          </div>

          {/* Error */}
          {mutation.isError && (
            <div className="flex items-start gap-2.5 p-3.5 bg-red-500/[0.08] border border-red-500/20 rounded-xl text-sm text-red-300">
              <AlertCircle className="w-4 h-4 mt-0.5 shrink-0 text-red-400" />
              <span>{extractApiError(mutation.error, 'Failed to create route')}</span>
            </div>
          )}

          {/* Builder redirect info */}
          <div className="flex items-start gap-2.5 p-3.5 bg-indigo-500/[0.06] border border-indigo-500/20 rounded-xl text-xs text-indigo-300">
            <Network className="w-4 h-4 mt-0.5 shrink-0 text-indigo-400" />
            <span>
              After creating, you'll be taken to the <strong>Workflow Builder</strong> to add
              filters and connect nodes. Activate the route when ready.
            </span>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-2 pt-1">
            <button type="button" onClick={onClose}
              className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors">
              Cancel
            </button>
            <button type="submit" disabled={mutation.isPending}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20">
              {mutation.isPending ? 'Creating…' : 'Create & Open Builder'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
