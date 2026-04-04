import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { certVaultApi } from '../../api/certVaultApi'
import type { CertificateDto } from '../../types'
import { X, Link, Loader2, AlertCircle, Info, ChevronDown } from 'lucide-react'
import { cn } from '../../lib/utils'
import { extractApiError } from '../../lib/errorUtils'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'

interface Props {
  cert:      CertificateDto
  tenantId:  string
  onClose:   () => void
  onSuccess: () => void
}

export default function CertGatewayMapModal({ cert, tenantId, onClose, onSuccess }: Props) {
  const [gatewayLogicalId, setGatewayLogicalId] = useState(cert.gatewayTlsLogicalId ?? '')
  const [useCustom, setUseCustom] = useState(!cert.gatewayTlsLogicalId)
  const [error, setError] = useState('')

  // Fetch active certs to show already-used TLS logical IDs as suggestions
  const { data: activeCerts = [] } = useRealtimeQuery({
    queryKey: ['certs-active', tenantId],
    queryFn:  () => certVaultApi.listActiveCertificates(tenantId),
    enabled: !!tenantId,
    wsEvents: ['certificate'],
  })

  // Collect unique gateway TLS logical IDs already in use from vault certs (excluding current cert)
  const vaultMappedSlots = Array.from(
    new Set(
      activeCerts
        .filter(c => c.gatewayTlsLogicalId && c.id !== cert.id)
        .map(c => c.gatewayTlsLogicalId as string)
    )
  )

  const mapMutation = useMutation({
    mutationFn: () => certVaultApi.mapToGateway(cert.id, tenantId, gatewayLogicalId),
    onSuccess:  () => onSuccess(),
    onError:    (e: unknown) => setError(extractApiError(e, 'Mapping failed')),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!gatewayLogicalId.trim()) { setError('Gateway TLS logical ID is required'); return }
    mapMutation.mutate()
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative w-full max-w-md bg-[#0f1117] border border-white/[0.08] rounded-2xl shadow-2xl overflow-hidden animate-scale-in">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div>
            <h2 className="text-base font-bold text-white flex items-center gap-2">
              <Link className="w-4 h-4 text-indigo-400" />
              Map to Gateway TLS
            </h2>
            <p className="text-xs text-gray-500 mt-0.5">
              Link <span className="text-white font-medium">{cert.alias}</span> to a gateway TLS slot
            </p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-white/[0.06] text-gray-500 hover:text-white transition-colors">
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

          <div className="p-3 rounded-lg bg-indigo-500/5 border border-indigo-500/20 flex items-start gap-2">
            <Info className="w-4 h-4 text-indigo-400 shrink-0 mt-0.5" />
            <p className="text-xs text-indigo-300/90 leading-relaxed">
              The gateway TLS logical ID is the key used by the gateway's in-memory certificate registry.
              Once mapped, the gateway will automatically load this certificate — no reload required.
              Use the <span className="font-semibold text-indigo-300">CertRotation</span> filter on a route to enforce it.
              <br />
              <span className="text-violet-300/80 mt-1 block">
                💡 For rotation and multi-cert grouping, consider using{' '}
                <span className="font-semibold text-violet-300">Certificate Groups</span> (see the Groups tab)
                — the group's logical ID becomes the stable gateway binding key.
              </span>
            </p>
          </div>

          {/* Slot picker — reuse existing vault-mapped IDs or enter a new one */}
          <div>
            <label className="block text-xs font-semibold text-gray-400 mb-2">
              Gateway TLS Logical ID <span className="text-red-400">*</span>
            </label>

            {/* Vault-mapped slots (logical IDs already in use by other certs) */}
            {vaultMappedSlots.length > 0 && (
              <div className="mb-3">
                <p className="text-[11px] text-gray-500 mb-1.5">Existing vault slots:</p>
                <div className="flex flex-wrap gap-1.5">
                  {vaultMappedSlots.map(slot => (
                    <button
                      key={slot}
                      type="button"
                      onClick={() => { setGatewayLogicalId(slot); setUseCustom(false) }}
                      className={cn(
                        'px-2.5 py-1 rounded-md text-xs font-mono border transition-all',
                        gatewayLogicalId === slot && !useCustom
                          ? 'bg-indigo-500/20 text-indigo-300 border-indigo-500/40'
                          : 'bg-white/[0.03] text-gray-400 border-white/[0.08] hover:text-white hover:border-white/20'
                      )}
                    >
                      {slot}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* New slot button (only when there are existing suggestions) */}
            {vaultMappedSlots.length > 0 && (
              <div className="mb-3">
                <button
                  type="button"
                  onClick={() => { setUseCustom(true); setGatewayLogicalId('') }}
                  className={cn(
                    'px-2.5 py-1 rounded-md text-xs border transition-all flex items-center gap-1',
                    useCustom
                      ? 'bg-indigo-500/20 text-indigo-300 border-indigo-500/40'
                      : 'bg-white/[0.03] text-gray-500 border-white/[0.08] hover:text-gray-300'
                  )}
                >
                  <ChevronDown className="w-3 h-3" />
                  New custom slot
                </button>
              </div>
            )}

            {/* Custom / new ID input */}
            {(vaultMappedSlots.length === 0 || useCustom) && (
              <input
                type="text"
                value={gatewayLogicalId}
                onChange={e => setGatewayLogicalId(e.target.value)}
                placeholder="e.g. my-inbound-tls"
                autoFocus
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/[0.08] rounded-lg text-sm text-white font-mono placeholder-gray-600 focus:outline-none focus:border-indigo-500/50 focus:ring-1 focus:ring-indigo-500/20"
              />
            )}
            <p className="text-[11px] text-gray-600 mt-1.5">
              URL-safe string. This ID is used in the <code className="text-gray-500">CertRotation</code> filter on routes.
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
              disabled={mapMutation.isPending || !gatewayLogicalId.trim()}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-lg transition-all disabled:opacity-60"
            >
              {mapMutation.isPending ? (
                <><Loader2 className="w-4 h-4 animate-spin" /> Mapping…</>
              ) : (
                <><Link className="w-4 h-4" /> Apply Mapping</>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
