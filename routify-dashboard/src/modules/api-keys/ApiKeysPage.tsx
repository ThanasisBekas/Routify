import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Key, Plus, RotateCcw, Ban, Copy, Check, Clock } from 'lucide-react'
import { apiKeysApi } from '../../api/apiKeysApi'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import { useAuthStore } from '../../store/authStore'
import { extractApiError, cn } from '../../lib/utils'
import type { ApiKeyDto, ApiKeyCreatedResponse, ApiKeyStatus } from '../../types'
import CreateApiKeyModal from './CreateApiKeyModal'

const STATUS_STYLES: Record<ApiKeyStatus, { label: string; className: string }> = {
  ACTIVE: { label: 'Active', className: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' },
  REVOKED: { label: 'Revoked', className: 'bg-red-500/10 text-red-400 border-red-500/20' },
  EXPIRED: { label: 'Expired', className: 'bg-gray-500/10 text-gray-400 border-gray-500/20' },
}

export default function ApiKeysPage() {
  useDocumentTitle('API Keys')
  const user = useAuthStore((s) => s.user)
  const qc = useQueryClient()
  const isAdmin = user?.role === 'SUPER_ADMIN' || user?.role === 'TENANT_ADMIN'

  const [showCreate, setShowCreate] = useState(false)
  const [createdKey, setCreatedKey] = useState<ApiKeyCreatedResponse | null>(null)
  const [copiedKey, setCopiedKey] = useState(false)
  const [page, setPage] = useState(0)

  const { data, isLoading } = useQuery({
    queryKey: ['api-keys', page],
    queryFn: () => apiKeysApi.list({ page, size: 20 }),
  })

  const revokeMutation = useMutation({
    mutationFn: (id: string) => apiKeysApi.revoke(id),
    onSuccess: () => {
      toast.success('API key revoked')
      qc.invalidateQueries({ queryKey: ['api-keys'] })
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const rotateMutation = useMutation({
    mutationFn: (id: string) => apiKeysApi.rotate(id),
    onSuccess: (result) => {
      setCreatedKey(result)
      toast.success('API key rotated — copy the new key now')
      qc.invalidateQueries({ queryKey: ['api-keys'] })
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text)
    setCopiedKey(true)
    setTimeout(() => setCopiedKey(false), 2000)
  }

  const formatDate = (iso?: string) => {
    if (!iso) return '—'
    return new Date(iso).toLocaleDateString('en-US', {
      month: 'short', day: 'numeric', year: 'numeric',
    })
  }

  const now = Date.now()
  const isExpiringSoon = (expiresAt?: string) => {
    if (!expiresAt) return false
    const diff = new Date(expiresAt).getTime() - now
    return diff > 0 && diff < 7 * 24 * 60 * 60 * 1000 // 7 days
  }

  const keys = data?.content ?? []

  return (
    <div className="flex-1 overflow-auto p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-bold text-white flex items-center gap-2">
            <Key className="w-5 h-5 text-indigo-400" />
            API Keys
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            Manage API keys for machine-to-machine authentication
          </p>
        </div>
        {isAdmin && (
          <button
            onClick={() => setShowCreate(true)}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" />
            Create API Key
          </button>
        )}
      </div>

      {/* Created key banner — shown once after create/rotate */}
      {createdKey && (
        <div className="mb-6 p-4 rounded-xl bg-amber-500/10 border border-amber-500/20">
          <div className="flex items-center gap-2 text-amber-400 text-sm font-medium mb-2">
            <Key className="w-4 h-4" />
            New API Key Created — Copy it now! It won't be shown again.
          </div>
          <div className="flex items-center gap-2">
            <code className="flex-1 px-3 py-2 bg-black/30 rounded-lg text-amber-300 text-sm font-mono break-all">
              {createdKey.rawKey}
            </code>
            <button
              onClick={() => copyToClipboard(createdKey.rawKey)}
              className="shrink-0 p-2 rounded-lg bg-amber-500/20 hover:bg-amber-500/30 text-amber-400 transition-colors"
              title="Copy to clipboard"
            >
              {copiedKey ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
            </button>
          </div>
          <button
            onClick={() => setCreatedKey(null)}
            className="mt-2 text-xs text-amber-500/60 hover:text-amber-400 transition-colors"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Table */}
      <div className="rounded-xl border border-white/[0.06] bg-[#0c0e14] overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-white/[0.06]">
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Name</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Key Prefix</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Role</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Expires</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Created</th>
              {isAdmin && (
                <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
              )}
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-600">
                  <div className="w-5 h-5 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin mx-auto" />
                </td>
              </tr>
            ) : keys.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-600">
                  No API keys yet.{isAdmin && ' Create one to get started.'}
                </td>
              </tr>
            ) : (
              keys.map((key: ApiKeyDto) => {
                const status = STATUS_STYLES[key.status]
                return (
                  <tr key={key.id} className="border-b border-white/[0.04] hover:bg-white/[0.02] transition-colors">
                    <td className="px-4 py-3">
                      <div className="font-medium text-white">{key.name}</div>
                      {key.email && <div className="text-xs text-gray-500">{key.email}</div>}
                    </td>
                    <td className="px-4 py-3">
                      <code className="text-xs text-indigo-400 bg-indigo-500/10 px-2 py-0.5 rounded">
                        {key.keyPrefix}…
                      </code>
                    </td>
                    <td className="px-4 py-3 text-gray-400">{key.role}</td>
                    <td className="px-4 py-3">
                      <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border', status.className)}>
                        {status.label}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-400">
                      <div className="flex items-center gap-1.5">
                        {isExpiringSoon(key.expiresAt) && (
                          <Clock className="w-3.5 h-3.5 text-amber-400" />
                        )}
                        {formatDate(key.expiresAt)}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-gray-500 text-xs">{formatDate(key.createdAt)}</td>
                    {isAdmin && (
                      <td className="px-4 py-3 text-right">
                        {key.status === 'ACTIVE' && (
                          <div className="flex items-center justify-end gap-1.5">
                            <button
                              onClick={() => rotateMutation.mutate(key.id)}
                              disabled={rotateMutation.isPending}
                              className="p-1.5 rounded-md text-gray-500 hover:text-indigo-400 hover:bg-indigo-500/10 transition-colors"
                              title="Rotate key"
                            >
                              <RotateCcw className="w-3.5 h-3.5" />
                            </button>
                            <button
                              onClick={() => {
                                if (confirm('Revoke this API key? This action cannot be undone.')) {
                                  revokeMutation.mutate(key.id)
                                }
                              }}
                              disabled={revokeMutation.isPending}
                              className="p-1.5 rounded-md text-gray-500 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                              title="Revoke key"
                            >
                              <Ban className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        )}
                      </td>
                    )}
                  </tr>
                )
              })
            )}
          </tbody>
        </table>

        {/* Pagination */}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-white/[0.06]">
            <span className="text-xs text-gray-500">
              Page {page + 1} of {data.totalPages} ({data.totalElements} keys)
            </span>
            <div className="flex gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="px-3 py-1 text-xs rounded-md border border-white/10 text-gray-400 hover:text-white disabled:opacity-40 transition-colors"
              >
                Previous
              </button>
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={page >= data.totalPages - 1}
                className="px-3 py-1 text-xs rounded-md border border-white/10 text-gray-400 hover:text-white disabled:opacity-40 transition-colors"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>

      {showCreate && (
        <CreateApiKeyModal
          onClose={() => setShowCreate(false)}
          onCreated={(result) => {
            setCreatedKey(result)
            setShowCreate(false)
          }}
        />
      )}
    </div>
  )
}

