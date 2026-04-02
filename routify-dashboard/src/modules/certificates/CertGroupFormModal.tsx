import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { certVaultApi } from '../../api/certVaultApi'
import type { CertGroupDto } from '../../types'
import { X, Layers, Loader2, AlertCircle, Info } from 'lucide-react'
import { extractApiError } from '../../lib/errorUtils'

interface Props {
  tenantId: string
  /** When provided, the form is in edit mode */
  group?: CertGroupDto
  onClose: () => void
  onSuccess: () => void
}

export default function CertGroupFormModal({ tenantId, group, onClose, onSuccess }: Props) {
  const isEdit = !!group

  const [logicalId,   setLogicalId]   = useState(group?.logicalId   ?? '')
  const [alias,       setAlias]       = useState(group?.alias        ?? '')
  const [description, setDescription] = useState(group?.description  ?? '')
  const [error,       setError]       = useState('')

  const createMutation = useMutation({
    mutationFn: () => certVaultApi.createGroup(tenantId, { logicalId, alias, description: description || undefined }),
    onSuccess:  () => onSuccess(),
    onError:    (e: unknown) => setError(extractApiError(e, 'Creation failed')),
  })

  const updateMutation = useMutation({
    mutationFn: () => certVaultApi.updateGroup(group!.id, tenantId, { alias, description: description || undefined }),
    onSuccess:  () => onSuccess(),
    onError:    (e: unknown) => setError(extractApiError(e, 'Update failed')),
  })

  const isPending = createMutation.isPending || updateMutation.isPending

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!isEdit && !logicalId.trim()) { setError('Logical ID is required'); return }
    if (!alias.trim()) { setError('Alias is required'); return }
    if (!isEdit && !/^[a-z0-9][a-z0-9\-_.]*$/.test(logicalId)) {
      setError('Logical ID must be URL-safe (lowercase alphanumeric, hyphens, underscores, dots)')
      return
    }
    if (isEdit) { updateMutation.mutate() } else { createMutation.mutate() }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative w-full max-w-md bg-[#0f1117] border border-white/[0.08] rounded-2xl shadow-2xl overflow-hidden animate-scale-in">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div>
            <h2 className="text-base font-bold text-white flex items-center gap-2">
              <Layers className="w-4 h-4 text-violet-400" />
              {isEdit ? 'Edit Certificate Group' : 'Create Certificate Group'}
            </h2>
            <p className="text-xs text-gray-500 mt-0.5">
              {isEdit
                ? `Editing group "${group!.alias}"`
                : 'Create a logical group to bundle certificates for gateway rotation'}
            </p>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-white/[0.06] text-gray-500 hover:text-white transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {error && (
            <div className="flex items-start gap-2 p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm">
              <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
              {error}
            </div>
          )}

          {!isEdit && (
            <div className="p-3 rounded-lg bg-violet-500/5 border border-violet-500/20 flex items-start gap-2">
              <Info className="w-4 h-4 text-violet-400 shrink-0 mt-0.5" />
              <p className="text-xs text-violet-300/90 leading-relaxed">
                The group's <span className="font-semibold text-violet-300">Logical ID</span> is
                the stable key used by the gateway TLS registry and the{' '}
                <code className="text-violet-300">CertRotation</code> filter. It cannot be changed
                after creation — choose carefully.
              </p>
            </div>
          )}

          {/* Logical ID — only shown on create */}
          {!isEdit && (
            <div>
              <label className="block text-xs font-semibold text-gray-400 mb-1.5">
                Logical ID <span className="text-red-400">*</span>
              </label>
              <input
                type="text"
                value={logicalId}
                onChange={e => setLogicalId(e.target.value.toLowerCase())}
                placeholder="e.g. my-api-inbound-tls"
                autoFocus
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/[0.08] rounded-lg text-sm text-white font-mono placeholder-gray-600 focus:outline-none focus:border-violet-500/50 focus:ring-1 focus:ring-violet-500/20"
              />
              <p className="text-[11px] text-gray-600 mt-1">
                URL-safe, lowercase. This ID is the gateway TLS registry key.
              </p>
            </div>
          )}

          {/* Alias */}
          <div>
            <label className="block text-xs font-semibold text-gray-400 mb-1.5">
              Alias <span className="text-red-400">*</span>
            </label>
            <input
              type="text"
              value={alias}
              onChange={e => setAlias(e.target.value)}
              placeholder="e.g. My API Inbound TLS Group"
              autoFocus={isEdit}
              className="w-full px-3 py-2 bg-white/[0.04] border border-white/[0.08] rounded-lg text-sm text-white placeholder-gray-600 focus:outline-none focus:border-violet-500/50 focus:ring-1 focus:ring-violet-500/20"
            />
          </div>

          {/* Description */}
          <div>
            <label className="block text-xs font-semibold text-gray-400 mb-1.5">
              Description
            </label>
            <textarea
              value={description}
              onChange={e => setDescription(e.target.value)}
              placeholder="Optional description…"
              rows={2}
              className="w-full px-3 py-2 bg-white/[0.04] border border-white/[0.08] rounded-lg text-sm text-white placeholder-gray-600 focus:outline-none focus:border-violet-500/50 focus:ring-1 focus:ring-violet-500/20 resize-none"
            />
          </div>

          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isPending}
              className="flex items-center gap-2 px-4 py-2 bg-violet-600 hover:bg-violet-500 text-white text-sm font-semibold rounded-lg transition-all disabled:opacity-60"
            >
              {isPending ? (
                <><Loader2 className="w-4 h-4 animate-spin" /> {isEdit ? 'Saving…' : 'Creating…'}</>
              ) : (
                <><Layers className="w-4 h-4" /> {isEdit ? 'Save Changes' : 'Create Group'}</>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

