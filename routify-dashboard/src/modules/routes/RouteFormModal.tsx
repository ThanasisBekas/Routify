/**
 * RouteFormModal — unified create / edit modal for routes.
 *
 * Single tab: "Route" — name, description, path, methods, upstream, strip-prefix.
 *
 * Filters are managed exclusively in the Workflow Builder (/routes/:id/builder).
 * On create: the user is redirected to the builder automatically.
 * On edit:   the user stays on this modal; a "Open Builder" link is shown.
 */
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { X, Route, AlertCircle, Zap, Network } from 'lucide-react'
import { toast } from 'sonner'
import { routesApi } from '../../api/routesApi'
import type { CreateRouteRequest, UpdateRouteRequest } from '../../types'
import { cn, extractApiError } from '../../lib/utils'
import { METHOD_OPTIONS, METHOD_COLORS_MODAL as METHOD_COLORS } from './routeConstants'

// ─── Schema ────────────────────────────────────────────────────────────────────

const schema = z.object({
  name: z.string().min(3).max(255),
  description: z.string().max(1000).optional(),
  pathPattern: z.string().min(1).startsWith('/'),
  methods: z.string().min(1),
  upstreamUri: z.string().regex(/^(https?:\/\/.+|lb:\/\/.+)/, 'Must be http(s):// or lb://'),
  stripPrefix: z.string().optional(),
})
type FormData = z.infer<typeof schema>

// ─── Shared style tokens ───────────────────────────────────────────────────────

const inputCls =
  'w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all'
const monoInputCls = `${inputCls} font-mono`

// ─── Props ─────────────────────────────────────────────────────────────────────

interface Props {
  /** Present → edit mode; absent → create mode */
  editingId?: string
  onClose: () => void
  onSaved: () => void
}

// ═══════════════════════════════════════════════════════════════════════════════
// RouteFormModal
// ═══════════════════════════════════════════════════════════════════════════════

export default function RouteFormModal({ editingId, onClose, onSaved }: Props) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const isEdit = !!editingId

  // ── Load existing route when editing ───────────────────────────────────────
  const { data: existing, isLoading: loadingExisting } = useQuery({
    queryKey: ['route', editingId],
    queryFn: () => routesApi.get(editingId!),
    enabled: isEdit,
  })

  // ── Form ───────────────────────────────────────────────────────────────────
  const {
    register,
    handleSubmit,
    watch,
    getValues,
    setValue,
    reset,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(schema),
    defaultValues: { methods: 'GET', pathPattern: '/' },
  })

  useEffect(() => {
    if (existing) {
      reset({
        name: existing.name,
        description: existing.description ?? '',
        pathPattern: existing.pathPattern,
        methods: existing.methods,
        upstreamUri: existing.upstreamUri,
        stripPrefix: existing.stripPrefix ?? '',
      })
    }
  }, [existing, reset])

  const selectedMethods = watch('methods')
    .split(',')
    .map((m) => m.trim())
    .filter(Boolean)

  const toggleMethod = (m: string) => {
    if (m === '*') {
      setValue('methods', '*')
      return
    }
    let current = getValues('methods')
      .split(',')
      .map((x) => x.trim())
      .filter((x) => x && x !== '*')
    if (current.includes(m)) current = current.filter((x) => x !== m)
    else current.push(m)
    setValue('methods', current.join(',') || 'GET')
  }

  // ── Create mutation — invalidate list and close; user opens the new route from the list ──
  const createMutation = useMutation({
    mutationFn: (data: CreateRouteRequest) => routesApi.create(data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['routes'] })
      onSaved()
      toast.success('Route created', {
        description: 'The route is being provisioned. Click it in the list to open the Workflow Builder.',
      })
    },
    onError: () => {
      /* errors shown inline */
    },
  })

  // ── Update mutation ─────────────────────────────────────────────────────────
  const updateMutation = useMutation({
    mutationFn: (data: UpdateRouteRequest) => routesApi.update(editingId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['routes'] })
      qc.invalidateQueries({ queryKey: ['route', editingId] })
      onSaved()
    },
  })

  const isPending = createMutation.isPending || updateMutation.isPending
  const submitError = createMutation.error ?? updateMutation.error

  const onSubmit = (data: FormData) => {
    if (isEdit) updateMutation.mutate(data)
    else createMutation.mutate(data)
  }

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4 animate-fade-in">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-2xl shadow-2xl flex flex-col max-h-[92vh] animate-fade-in-up">
        {/* ── Header ────────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06] shrink-0">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center shrink-0">
              <Route className="w-3.5 h-3.5 text-indigo-400" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-white leading-tight">
                {isEdit ? 'Edit Route' : 'Create New Route'}
              </h2>
              {existing && <p className="text-[11px] text-gray-500 mt-0.5 leading-tight">{existing.name}</p>}
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* ── Body ──────────────────────────────────────────────────────────── */}
        <div className="flex-1 overflow-y-auto">
          <form id="route-form" onSubmit={handleSubmit(onSubmit)} className="p-6 space-y-4">
            {/* Live route warning */}
            {existing?.status === 'ACTIVE' && (
              <div className="flex items-start gap-2.5 p-3.5 bg-amber-500/[0.07] border border-amber-500/20 rounded-xl text-xs text-amber-300">
                <Zap className="w-4 h-4 mt-0.5 shrink-0 text-amber-400" />
                <span>
                  This route is <strong>live</strong>. Changes to path, methods and upstream apply instantly via Kafka
                  hot-reload — no restart needed.
                </span>
              </div>
            )}

            {/* Name */}
            <div className="space-y-1.5">
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Route Name *</label>
              <input {...register('name')} placeholder="e.g. orders-api-v1" className={inputCls} />
              {errors.name && <p className="text-xs text-red-400">{errors.name.message}</p>}
            </div>

            {/* Description */}
            <div className="space-y-1.5">
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                Description <span className="normal-case font-normal text-gray-600">optional</span>
              </label>
              <textarea
                {...register('description')}
                rows={2}
                placeholder="What does this route handle?"
                className={`${inputCls} resize-none`}
              />
            </div>

            {/* Path Pattern */}
            <div className="space-y-1.5">
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                Path Pattern *
              </label>
              <input {...register('pathPattern')} placeholder="/api/v1/orders/**" className={monoInputCls} />
              <p className="text-[11px] text-gray-600 pl-0.5">
                Wildcards: <code className="font-mono">/api/**</code> · Path vars:{' '}
                <code className="font-mono">/users/&#123;id&#125;</code>
              </p>
              {errors.pathPattern && <p className="text-xs text-red-400">{errors.pathPattern.message}</p>}
            </div>

            {/* HTTP Methods */}
            <div className="space-y-2">
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                HTTP Methods *
              </label>
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
                          ? (METHOD_COLORS[m] ?? 'bg-indigo-600 text-white border-indigo-500/50')
                          : 'bg-white/[0.04] text-gray-500 border-white/[0.08] hover:border-white/20 hover:text-gray-300',
                      )}
                    >
                      {m}
                    </button>
                  )
                })}
              </div>
              {errors.methods && <p className="text-xs text-red-400">{errors.methods.message}</p>}
            </div>

            {/* Upstream URI */}
            <div className="space-y-1.5">
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                Upstream URI *
              </label>
              <input
                {...register('upstreamUri')}
                placeholder="http://orders-service:8080 or lb://orders-service"
                className={monoInputCls}
              />
              <p className="text-[11px] text-gray-600 pl-0.5">
                Use <code className="font-mono">lb://</code> for load-balanced service discovery
              </p>
              {errors.upstreamUri && <p className="text-xs text-red-400">{errors.upstreamUri.message}</p>}
            </div>

            {/* Strip Prefix */}
            <div className="space-y-1.5">
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                Strip Prefix <span className="normal-case font-normal text-gray-600">optional</span>
              </label>
              <input
                {...register('stripPrefix')}
                placeholder="/api/v1 (stripped before forwarding)"
                className={monoInputCls}
              />
            </div>

            {/* Submit error */}
            {submitError && (
              <div className="flex items-start gap-2.5 p-3.5 bg-red-500/[0.08] border border-red-500/20 rounded-xl text-sm text-red-300">
                <AlertCircle className="w-4 h-4 mt-0.5 shrink-0 text-red-400" />
                <span>{extractApiError(submitError, 'Failed to save route')}</span>
              </div>
            )}

            {/* Create mode info */}
            {!isEdit && (
              <div className="flex items-start gap-2.5 p-3.5 bg-indigo-500/[0.06] border border-indigo-500/20 rounded-xl text-xs text-indigo-300">
                <Network className="w-4 h-4 mt-0.5 shrink-0 text-indigo-400" />
                <span>
                  After creating, the route will appear in the list. Click it to open the{' '}
                  <strong>Workflow Builder</strong> and attach filters.
                </span>
              </div>
            )}

            {/* Edit mode: open builder link */}
            {isEdit && existing && (
              <div className="flex items-center justify-between p-3.5 bg-white/[0.02] border border-white/[0.06] rounded-xl">
                <div className="text-xs text-gray-400">Manage filters and connections in the Workflow Builder</div>
                <button
                  type="button"
                  onClick={() => {
                    onClose()
                    navigate(`/routes/${existing.id}/builder`)
                  }}
                  className="flex items-center gap-1.5 text-xs font-semibold text-indigo-400 hover:text-indigo-300 transition-colors px-2.5 py-1.5 rounded-lg hover:bg-indigo-500/10 border border-transparent hover:border-indigo-500/20"
                >
                  <Network className="w-3.5 h-3.5" />
                  Open Builder
                </button>
              </div>
            )}
          </form>
        </div>

        {/* ── Footer ────────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-end gap-2 px-6 py-4 border-t border-white/[0.06] shrink-0">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors"
          >
            Cancel
          </button>
          <button
            type="submit"
            form="route-form"
            disabled={isPending || (isEdit && loadingExisting)}
            className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20"
          >
            {isPending ? (isEdit ? 'Saving…' : 'Creating…') : isEdit ? 'Save Changes' : 'Create Route'}
          </button>
        </div>
      </div>
    </div>
  )
}
