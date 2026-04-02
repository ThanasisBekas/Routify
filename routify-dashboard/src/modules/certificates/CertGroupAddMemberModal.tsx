import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { certVaultApi } from '../../api/certVaultApi'
import type { CertGroupDto, CertificateDto } from '../../types'
import { X, Plus, Loader2, AlertCircle, ShieldCheck, CheckCircle2 } from 'lucide-react'
import { extractApiError } from '../../lib/errorUtils'
import { cn } from '../../lib/utils'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'

interface Props {
  group:     CertGroupDto
  tenantId:  string
  onClose:   () => void
  onSuccess: () => void
}

export default function CertGroupAddMemberModal({ group, tenantId, onClose, onSuccess }: Props) {
  const [selectedCert, setSelectedCert] = useState<string>('')
  const [memberAlias,  setMemberAlias]  = useState('')
  const [error,        setError]        = useState('')

  // Fetch active certs not already in this group
  const { data: activeCerts = [], isLoading } = useRealtimeQuery({
    queryKey: ['certs-active', tenantId],
    queryFn:  () => certVaultApi.listActiveCertificates(tenantId),
    enabled:  !!tenantId,
    wsEvents: ['certificate'],
  })

  const existingMemberIds = new Set((group.members ?? []).map((m: CertificateDto) => m.id))
  const available = activeCerts.filter(c => !existingMemberIds.has(c.id) && !c.groupId)

  const addMutation = useMutation({
    mutationFn: () => certVaultApi.addMemberToGroup(group.id, tenantId, {
      certId: selectedCert,
      memberAlias: memberAlias.trim() || undefined,
    }),
    onSuccess: () => onSuccess(),
    onError:   (e: unknown) => setError(extractApiError(e, 'Failed to add member')),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!selectedCert) { setError('Please select a certificate'); return }
    addMutation.mutate()
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative w-full max-w-md bg-[#0f1117] border border-white/[0.08] rounded-2xl shadow-2xl overflow-hidden animate-scale-in">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div>
            <h2 className="text-base font-bold text-white flex items-center gap-2">
              <Plus className="w-4 h-4 text-violet-400" />
              Add Member to Group
            </h2>
            <p className="text-xs text-gray-500 mt-0.5">
              Adding to <span className="text-white font-medium font-mono">{group.logicalId}</span>
            </p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-white/[0.06] text-gray-500 hover:text-white transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {error && (
            <div className="flex items-start gap-2 p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm">
              <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
              {error}
            </div>
          )}

          {/* Certificate picker */}
          <div>
            <label className="block text-xs font-semibold text-gray-400 mb-2">
              Certificate <span className="text-red-400">*</span>
            </label>
            {isLoading ? (
              <div className="flex items-center gap-2 py-4 text-gray-500 text-sm">
                <Loader2 className="w-4 h-4 animate-spin" /> Loading…
              </div>
            ) : available.length === 0 ? (
              <p className="text-xs text-gray-500 py-3">
                No standalone active certificates available. Upload a new certificate or remove one
                from its current group first.
              </p>
            ) : (
              <div className="flex flex-col gap-1.5 max-h-48 overflow-y-auto pr-1">
                {available.map(cert => (
                  <button
                    key={cert.id}
                    type="button"
                    onClick={() => setSelectedCert(cert.id)}
                    className={cn(
                      'flex items-center gap-2.5 px-3 py-2 rounded-lg text-xs border transition-all text-left',
                      selectedCert === cert.id
                        ? 'bg-violet-500/20 text-violet-300 border-violet-500/40'
                        : 'bg-white/[0.03] text-gray-400 border-white/[0.08] hover:text-white hover:border-white/20'
                    )}
                  >
                    <div className="w-6 h-6 rounded-md bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center shrink-0">
                      {selectedCert === cert.id
                        ? <CheckCircle2 className="w-3 h-3 text-violet-400" />
                        : <ShieldCheck className="w-3 h-3 text-indigo-400" />
                      }
                    </div>
                    <div className="min-w-0">
                      <p className="font-medium text-white truncate">{cert.alias}</p>
                      <p className="font-mono text-[10px] text-gray-500">{cert.logicalId}</p>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Member alias */}
          <div>
            <label className="block text-xs font-semibold text-gray-400 mb-1.5">
              Member Alias <span className="text-gray-600">(optional)</span>
            </label>
            <input
              type="text"
              value={memberAlias}
              onChange={e => setMemberAlias(e.target.value)}
              placeholder="e.g. primary, backup-2025, ecdsa-leaf"
              className="w-full px-3 py-2 bg-white/[0.04] border border-white/[0.08] rounded-lg text-sm text-white font-mono placeholder-gray-600 focus:outline-none focus:border-violet-500/50 focus:ring-1 focus:ring-violet-500/20"
            />
            <p className="text-[11px] text-gray-600 mt-1">
              Short label distinguishing this cert within the group. Must be unique within the group.
            </p>
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
              disabled={addMutation.isPending || !selectedCert}
              className="flex items-center gap-2 px-4 py-2 bg-violet-600 hover:bg-violet-500 text-white text-sm font-semibold rounded-lg transition-all disabled:opacity-60"
            >
              {addMutation.isPending
                ? <><Loader2 className="w-4 h-4 animate-spin" /> Adding…</>
                : <><Plus className="w-4 h-4" /> Add to Group</>
              }
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

