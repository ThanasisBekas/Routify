import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { tenantsApi } from '../../api/tenantsApi'
import type { CreateWorkspaceRequest } from '../../api/tenantsApi'
import { useAuthStore } from '../../store/authStore'
import { cn } from '../../lib/utils'
import { extractApiError } from '../../lib/errorUtils'
import type { TenantDto, TenantPlan } from '../../types'
import {
  Building2, Plus, Loader2, AlertCircle, CheckCircle2, X,
  ShieldOff, RefreshCcw, Lock,
} from 'lucide-react'

// ─── Plan badge ───────────────────────────────────────────────────────────────

const PLAN_COLOR: Record<TenantPlan, string> = {
  FREE:       'text-gray-400 bg-gray-400/10 border-gray-400/20',
  STARTER:    'text-blue-400 bg-blue-400/10 border-blue-400/20',
  PRO:        'text-indigo-400 bg-indigo-400/10 border-indigo-400/20',
  ENTERPRISE: 'text-purple-400 bg-purple-400/10 border-purple-400/20',
}

const STATUS_COLOR: Record<TenantDto['status'], string> = {
  ACTIVE:    'text-green-400 bg-green-400/10 border-green-400/20',
  SUSPENDED: 'text-yellow-400 bg-yellow-400/10 border-yellow-400/20',
  DELETED:   'text-red-400 bg-red-400/10 border-red-400/20',
}

function Badge({ label, cls }: { label: string; cls: string }) {
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold border', cls)}>
      {label}
    </span>
  )
}

// ─── Create Workspace Modal ───────────────────────────────────────────────────

const PLAN_OPTIONS: TenantPlan[] = ['FREE', 'STARTER', 'PRO', 'ENTERPRISE']

function CreateWorkspaceModal({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<CreateWorkspaceRequest>({
    name: '',
    slug: '',
    plan: 'FREE',
    contactEmail: '',
  })
  const [error,   setError]   = useState<string | null>(null)
  const [success, setSuccess] = useState(false)

  const mutation = useMutation({
    mutationFn: () => tenantsApi.create(form),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      queryClient.invalidateQueries({ queryKey: ['tenants'] })
      setSuccess(true)
    },
    onError: (e: unknown) => setError(extractApiError(e, 'Failed to create workspace.')),
  })

  const slugify = (name: string) =>
    name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '')

  const inputCls =
    'w-full bg-white/[0.05] border border-white/[0.09] rounded-xl px-3 py-2.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/20 transition-all'

  if (success) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
        <div className="bg-[#0e1117] border border-white/[0.08] rounded-2xl shadow-2xl w-full max-w-sm p-8 flex flex-col items-center gap-4">
          <CheckCircle2 className="w-10 h-10 text-green-400" />
          <p className="text-white font-semibold text-lg">Workspace created!</p>
          <p className="text-gray-500 text-sm text-center">
            <code className="text-indigo-400">{form.slug}</code> is now available in the login dropdown.
          </p>
          <button onClick={onClose}
            className="mt-2 px-5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-xl transition-colors">
            Done
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="bg-[#0e1117] border border-white/[0.08] rounded-2xl shadow-2xl w-full max-w-md">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <Building2 className="w-4 h-4 text-indigo-400" />
            <h2 className="text-sm font-semibold text-white">New Workspace</h2>
          </div>
          <button onClick={onClose} className="p-1 rounded-lg text-gray-500 hover:text-gray-300 hover:bg-white/[0.06] transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="p-6 space-y-4">
          {/* Name */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Name</label>
            <input
              className={inputCls}
              placeholder="Acme Corp"
              value={form.name}
              onChange={e => setForm(f => ({
                ...f,
                name: e.target.value,
                slug: f.slug === slugify(f.name) ? slugify(e.target.value) : f.slug,
              }))}
            />
          </div>

          {/* Slug */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Slug</label>
            <input
              className={inputCls}
              placeholder="acme"
              value={form.slug}
              onChange={e => setForm(f => ({ ...f, slug: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '') }))}
            />
            <p className="text-[11px] text-gray-600 pl-1">URL-safe identifier used in the login dropdown</p>
          </div>

          {/* Plan */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Plan</label>
            <div className="grid grid-cols-2 gap-2">
              {PLAN_OPTIONS.map(plan => (
                <button
                  key={plan}
                  type="button"
                  onClick={() => setForm(f => ({ ...f, plan }))}
                  className={cn(
                    'py-2 px-3 rounded-lg text-xs font-semibold border transition-all',
                    form.plan === plan
                      ? 'bg-indigo-500/15 border-indigo-500/40 text-indigo-300'
                      : 'bg-white/[0.03] border-white/[0.07] text-gray-400 hover:bg-white/[0.06]',
                  )}
                >
                  {plan}
                </button>
              ))}
            </div>
          </div>

          {/* Contact email */}
          <div className="space-y-1.5">
            <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
              Contact Email <span className="text-gray-600 normal-case font-normal">(optional)</span>
            </label>
            <input
              type="email"
              className={inputCls}
              placeholder="admin@acme.example"
              value={form.contactEmail ?? ''}
              onChange={e => setForm(f => ({ ...f, contactEmail: e.target.value }))}
            />
          </div>

          {error && (
            <div className="flex items-start gap-2.5 p-3 rounded-xl bg-red-500/[0.08] border border-red-500/20">
              <AlertCircle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
              <p className="text-red-300 text-sm">{error}</p>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-3 px-6 py-4 border-t border-white/[0.06]">
          <button onClick={onClose}
            className="px-4 py-2 text-sm text-gray-400 hover:text-white rounded-xl hover:bg-white/[0.06] transition-colors">
            Cancel
          </button>
          <button
            onClick={() => { setError(null); mutation.mutate() }}
            disabled={mutation.isPending || !form.name.trim() || !form.slug.trim()}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-xl transition-colors"
          >
            {mutation.isPending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />}
            Create Workspace
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Workspaces Page ──────────────────────────────────────────────────────────

export default function WorkspacesPage() {
  const user           = useAuthStore(s => s.user)
  const isSuperAdmin   = user?.role === 'SUPER_ADMIN'
  const queryClient    = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)

  const { data, isLoading } = useQuery({
    queryKey: ['tenants'],
    queryFn: () => tenantsApi.list(0, 100),
  })

  const suspendMut = useMutation({
    mutationFn: (id: string) => tenantsApi.suspend(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tenants'] }),
  })

  const reactivateMut = useMutation({
    mutationFn: (id: string) => tenantsApi.reactivate(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tenants'] }),
  })

  const tenants = data?.content ?? []

  if (!isSuperAdmin) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-4 text-gray-500">
        <Lock className="w-8 h-8" />
        <p className="text-sm">Workspace management is restricted to Super Admins.</p>
      </div>
    )
  }

  return (
    <div className="p-6 max-w-5xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight flex items-center gap-2.5">
            <Building2 className="w-5 h-5 text-indigo-400" />
            Workspaces
          </h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Manage tenant workspaces. Each workspace has isolated routes, filters, and users.
          </p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-xl transition-colors shadow-lg shadow-indigo-500/20"
        >
          <Plus className="w-4 h-4" />
          New Workspace
        </button>
      </div>

      {/* Table */}
      <div className="rounded-xl border border-white/[0.07] overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-white/[0.06] bg-white/[0.02]">
              {['Name', 'Slug', 'Plan', 'Status', 'Created', ''].map(h => (
                <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-white/[0.04]">
            {isLoading
              ? Array.from({ length: 3 }).map((_, i) => (
                  <tr key={i}>
                    {Array.from({ length: 6 }).map((_, j) => (
                      <td key={j} className="px-4 py-3">
                        <div className="h-4 bg-white/[0.05] rounded animate-pulse w-24" />
                      </td>
                    ))}
                  </tr>
                ))
              : tenants.map(t => (
                  <tr key={t.id} className="hover:bg-white/[0.02] transition-colors">
                    <td className="px-4 py-3 text-white font-medium">{t.name}</td>
                    <td className="px-4 py-3">
                      <code className="text-indigo-400 text-xs bg-indigo-500/10 px-2 py-0.5 rounded">{t.slug}</code>
                    </td>
                    <td className="px-4 py-3">
                      <Badge label={t.plan} cls={PLAN_COLOR[t.plan]} />
                    </td>
                    <td className="px-4 py-3">
                      <Badge label={t.status} cls={STATUS_COLOR[t.status]} />
                    </td>
                    <td className="px-4 py-3 text-gray-500 text-xs">
                      {new Date(t.createdAt).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1 justify-end">
                        {t.status === 'ACTIVE' ? (
                          <button
                            onClick={() => suspendMut.mutate(t.id)}
                            disabled={suspendMut.isPending}
                            title="Suspend workspace"
                            className="p-1.5 rounded-lg text-gray-500 hover:text-yellow-400 hover:bg-yellow-500/10 transition-colors"
                          >
                            <ShieldOff className="w-4 h-4" />
                          </button>
                        ) : t.status === 'SUSPENDED' ? (
                          <button
                            onClick={() => reactivateMut.mutate(t.id)}
                            disabled={reactivateMut.isPending}
                            title="Reactivate workspace"
                            className="p-1.5 rounded-lg text-gray-500 hover:text-green-400 hover:bg-green-500/10 transition-colors"
                          >
                            <RefreshCcw className="w-4 h-4" />
                          </button>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                ))
            }
          </tbody>
        </table>

        {!isLoading && tenants.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 text-gray-500 gap-3">
            <Building2 className="w-8 h-8 opacity-40" />
            <p className="text-sm">No workspaces yet.</p>
          </div>
        )}
      </div>

      {showCreate && <CreateWorkspaceModal onClose={() => setShowCreate(false)} />}
    </div>
  )
}

