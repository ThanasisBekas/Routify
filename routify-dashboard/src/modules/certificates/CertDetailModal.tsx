import type { CertificateDto } from '../../types'
import { X, ShieldCheck, CheckCircle2, AlertTriangle, XCircle, Link, Link2Off, Key } from 'lucide-react'
import { cn } from '../../lib/utils'

interface Props {
  cert: CertificateDto
  onClose: () => void
}

function Row({ label, value }: { label: string; value?: React.ReactNode }) {
  if (!value) return null
  return (
    <div className="flex gap-3 py-2 border-b border-white/[0.04]">
      <span className="text-xs text-gray-500 w-40 shrink-0">{label}</span>
      <span className="text-xs text-gray-200 break-all font-mono">{value}</span>
    </div>
  )
}

function formatDate(iso?: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('en-GB', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export default function CertDetailModal({ cert, onClose }: Props) {
  const expiryColor =
    {
      VALID: 'text-emerald-400',
      EXPIRING_SOON: 'text-amber-400',
      EXPIRED: 'text-red-400',
    }[cert.expiryStatus] ?? 'text-gray-400'

  const ExpiryIcon =
    {
      VALID: CheckCircle2,
      EXPIRING_SOON: AlertTriangle,
      EXPIRED: XCircle,
    }[cert.expiryStatus] ?? CheckCircle2

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative w-full max-w-2xl bg-[#0f1117] border border-white/[0.08] rounded-2xl shadow-2xl overflow-hidden animate-scale-in">
        {/* Header */}
        <div className="flex items-start justify-between px-6 py-4 border-b border-white/[0.06]">
          <div className="flex items-start gap-3">
            <div className="w-10 h-10 rounded-xl bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center shrink-0 mt-0.5">
              <ShieldCheck className="w-5 h-5 text-indigo-400" />
            </div>
            <div>
              <h2 className="text-base font-bold text-white">{cert.alias}</h2>
              <p className="text-xs text-gray-500 font-mono mt-0.5">{cert.logicalId}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-white/[0.06] text-gray-500 hover:text-white transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Content */}
        <div className="p-6 overflow-y-auto max-h-[70vh] space-y-6">
          {/* Status badges */}
          <div className="flex items-center gap-3">
            <span
              className={cn(
                'inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold border',
                cert.status === 'ACTIVE'
                  ? 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20'
                  : cert.status === 'REVOKED'
                    ? 'text-red-400 bg-red-400/10 border-red-400/20'
                    : cert.status === 'EXPIRED'
                      ? 'text-orange-400 bg-orange-400/10 border-orange-400/20'
                      : 'text-gray-500 bg-gray-500/10 border-gray-500/20',
              )}
            >
              {cert.status}
            </span>
            <span
              className={cn(
                'inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold border',
                expiryColor,
                'bg-white/[0.03] border-white/[0.08]',
              )}
            >
              <ExpiryIcon className="w-3.5 h-3.5" />
              {cert.expiryStatus.replace('_', ' ')}
            </span>
            <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-white/[0.03] border border-white/[0.08] text-gray-400">
              {cert.format}
            </span>
            {cert.hasPrivateKey && (
              <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-emerald-500/5 border border-emerald-500/20 text-emerald-400">
                <Key className="w-3 h-3" />
                Private key
              </span>
            )}
          </div>

          {/* Certificate details */}
          <div>
            <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-widest mb-2">Certificate Details</h3>
            <div className="rounded-xl bg-white/[0.02] border border-white/[0.06] px-4">
              <Row label="Subject DN" value={cert.subjectDn} />
              <Row label="Issuer DN" value={cert.issuerDn} />
              <Row label="Serial Number" value={cert.serialNumber} />
              <Row label="Valid From" value={formatDate(cert.notBefore)} />
              <Row label="Valid Until" value={formatDate(cert.notAfter)} />
              <Row label="Signature Alg" value={cert.signatureAlg} />
              <Row
                label="Key Algorithm"
                value={
                  cert.keyAlgorithm ? `${cert.keyAlgorithm}${cert.keySize ? ` ${cert.keySize}-bit` : ''}` : undefined
                }
              />
              <Row label="Is CA" value={cert.isCa ? 'Yes' : 'No'} />
              {cert.sanDns && cert.sanDns.length > 0 && <Row label="SAN (DNS)" value={cert.sanDns.join(', ')} />}
              {cert.sanIp && cert.sanIp.length > 0 && <Row label="SAN (IP)" value={cert.sanIp.join(', ')} />}
            </div>
          </div>

          {/* Fingerprints */}
          {(cert.fingerprintSha1 || cert.fingerprintSha256) && (
            <div>
              <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-widest mb-2">Fingerprints</h3>
              <div className="rounded-xl bg-white/[0.02] border border-white/[0.06] px-4">
                <Row label="SHA-1" value={cert.fingerprintSha1} />
                <Row label="SHA-256" value={cert.fingerprintSha256} />
              </div>
            </div>
          )}

          {/* Gateway mapping */}
          <div>
            <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-widest mb-2">Gateway TLS Mapping</h3>
            <div className="rounded-xl bg-white/[0.02] border border-white/[0.06] px-4 py-3">
              {cert.gatewayTlsLogicalId ? (
                <div className="space-y-2">
                  <div className="flex items-center gap-2">
                    <Link className="w-4 h-4 text-indigo-400 shrink-0" />
                    <span className="text-sm text-indigo-300 font-mono">{cert.gatewayTlsLogicalId}</span>
                    <span className="ml-auto inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/10 border border-emerald-500/20 text-emerald-400">
                      <CheckCircle2 className="w-2.5 h-2.5" />
                      Active in gateway
                    </span>
                  </div>
                  <p className="text-[11px] text-gray-600">
                    This certificate is loaded into the gateway's in-memory TLS registry under this logical ID. Use the{' '}
                    <code className="text-gray-500">CertRotation</code> filter on routes to enforce it.
                  </p>
                </div>
              ) : (
                <div className="flex items-center gap-2">
                  <Link2Off className="w-4 h-4 text-gray-600 shrink-0" />
                  <p className="text-xs text-gray-500">Not mapped to any gateway TLS slot</p>
                </div>
              )}
            </div>
          </div>

          {/* Upload metadata */}
          <div>
            <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-widest mb-2">Metadata</h3>
            <div className="rounded-xl bg-white/[0.02] border border-white/[0.06] px-4">
              <Row label="Uploaded by" value={cert.uploadedBy} />
              <Row label="Uploaded at" value={formatDate(cert.createdAt)} />
              <Row
                label="Updated at"
                value={cert.updatedAt !== cert.createdAt ? formatDate(cert.updatedAt) : undefined}
              />
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="flex justify-end px-6 py-4 border-t border-white/[0.06] bg-[#0c0e14]">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
