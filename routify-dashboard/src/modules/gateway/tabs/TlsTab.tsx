/**
 * TlsTab — TLS / Certificate Store configuration.
 * (Content preserved from original; styled with new primitives)
 */
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Server, ShieldCheck, FolderOpen, Plus, Trash2, RotateCcw,
  ExternalLink, CheckCircle, Clock, AlertTriangle, XCircle,
  CheckCircle2, Key, Layers, Users, Link,
} from 'lucide-react'
import { certVaultApi } from '../../../api/certVaultApi'
import { gatewayApi } from '../../../api/gatewayApi'
import { useAuthStore } from '../../../store/authStore'
import { useRealtimeQuery } from '../../../hooks/useRealtimeQuery'
import { cn } from '../../../lib/utils'
import type { GatewayTlsConfig, CertGroupDto } from '../../../types'
import {
  SectionHeader, ToggleRow, Field, SaveBar, EmptyState,
  monoInputCls, InfoBanner,
} from '../components/GatewayPrimitives'

interface Props {
  initial: GatewayTlsConfig
  onSave: (v: GatewayTlsConfig) => void
  isPending: boolean
}

export default function TlsTab({ initial, onSave, isPending }: Props) {
  const [cfg, setCfg] = useState<GatewayTlsConfig>(initial)
  const dirty = JSON.stringify(cfg) !== JSON.stringify(initial)
  const { user } = useAuthStore()
  const tenantId = user?.tenantId ?? ''
  const navigate = useNavigate()

  const certStatusColor: Record<string, string> = {
    VALID:         'text-emerald-400',
    EXPIRING_SOON: 'text-amber-400',
    EXPIRED:       'text-red-400',
  }

  const { data: liveRegistry, isLoading: liveLoading, refetch: refetchLive } = useRealtimeQuery({
    queryKey: ['gateway-live-certs'],
    queryFn: gatewayApi.getLiveCertificates,
    staleTime: 30_000,
    wsEvents: ['gateway', 'certificate'],
  })

  const { data: groupsPage, isLoading: groupsLoading, refetch: refetchGroups } = useRealtimeQuery({
    queryKey: ['cert-groups', tenantId, 'ACTIVE'],
    queryFn: () => certVaultApi.listGroups({ tenantId, status: 'ACTIVE', size: 100 }),
    enabled: !!tenantId,
    staleTime: 30_000,
    wsEvents: ['certificate'],
  })

  const activeGroups: CertGroupDto[] = (groupsPage as { content: CertGroupDto[] } | undefined)?.content ?? []

  const groupDetailsQuery = useRealtimeQuery({
    queryKey: ['cert-groups-details-tls', tenantId, activeGroups.map(g => g.id).join(',')],
    queryFn: async () => {
      if (activeGroups.length === 0) return []
      return Promise.all(activeGroups.map(g => certVaultApi.getGroup(g.id, tenantId)))
    },
    enabled: !!tenantId && activeGroups.length > 0,
    staleTime: 30_000,
    wsEvents: ['certificate'],
  })
  const groupsWithMembers: CertGroupDto[] = (groupDetailsQuery.data as CertGroupDto[] | undefined) ?? activeGroups

  const liveEntries = liveRegistry as Record<string, {
    status?: string; fingerprint?: string; notAfter?: string; source?: string
  }> | undefined

  const expiryIcon = (status?: string) => {
    if (status === 'EXPIRING_SOON') return <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />
    if (status === 'EXPIRED')       return <XCircle       className="w-3.5 h-3.5 text-red-400" />
    return                                  <CheckCircle2  className="w-3.5 h-3.5 text-emerald-400" />
  }

  return (
    <div className="max-w-3xl">
      <SectionHeader
        icon={Server}
        title="TLS / Certificate Store"
        description="Manage certificate file sources, directory watchers, and Certificate Vault group bindings for mTLS hot-reload."
      />

      {/* Timing */}
      <div className="grid grid-cols-2 gap-4 mb-8">
        <Field label="Expiry Warning Threshold" hint="Alert when a cert expires within this window (e.g. 30d)">
          <input value={cfg.expiryWarning} onChange={e => setCfg(p => ({ ...p, expiryWarning: e.target.value }))} className={monoInputCls} placeholder="30d" />
        </Field>
        <Field label="File Watch Interval" hint="How often to check for cert changes on disk (e.g. 30s)">
          <input value={cfg.fileWatchInterval} onChange={e => setCfg(p => ({ ...p, fileWatchInterval: e.target.value }))} className={monoInputCls} placeholder="30s" />
        </Field>
      </div>

      {/* ── Certificate Vault panel ──────────────────────────────────────────── */}
      <div className="mb-8">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-indigo-400" />
            <span className="text-sm font-semibold text-white">Certificate Vault — Gateway Mappings</span>
            <span className="text-[10px] text-gray-500 px-2 py-0.5 rounded-full bg-white/[0.04] border border-white/[0.06]">
              groups
            </span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => { refetchGroups(); refetchLive(); groupDetailsQuery.refetch() }}
              className="flex items-center gap-1 text-xs text-gray-400 hover:text-white px-2 py-1 rounded-md hover:bg-white/[0.05] transition-colors"
            >
              <RotateCcw className="w-3 h-3" /> Refresh
            </button>
            <button
              onClick={() => navigate('/certificates')}
              className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300 px-2 py-1 rounded-md hover:bg-indigo-500/10 transition-colors"
            >
              <ExternalLink className="w-3 h-3" /> Manage Vault
            </button>
          </div>
        </div>

        <InfoBanner variant="info">
          <span className="font-semibold text-indigo-300">How it works: </span>
          Each <span className="text-violet-300 font-medium">certificate group</span> has a stable logical ID the gateway TLS
          registry keys on. Upload certs into a group — the gateway loads them automatically. Rotate by adding a new cert;
          revoke the old one with zero downtime.
        </InfoBanner>

        <div className="mt-3">
          {groupsLoading ? (
            <div className="flex items-center gap-2 py-6 text-sm text-gray-500">
              <div className="w-4 h-4 border border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
              Loading certificate groups…
            </div>
          ) : activeGroups.length === 0 ? (
            <EmptyState
              icon={Layers}
              title="No certificate groups configured"
              description="Go to Certificate Vault → create a group with a logical ID → upload certs into it"
              action={
                <button
                  onClick={() => navigate('/certificates')}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-xs font-medium rounded-lg transition-all"
                >
                  <ExternalLink className="w-3.5 h-3.5" /> Open Certificate Vault
                </button>
              }
            />
          ) : (
            <div className="space-y-3 mt-3">
              {groupsWithMembers.map(group => {
                const liveEntry = liveEntries?.[group.logicalId]
                const isLoaded  = liveEntry != null
                return (
                  <div key={group.id} className="bg-white/[0.03] rounded-xl border border-white/[0.06] p-4">
                    <div className="flex items-start justify-between gap-4">
                      <div className="flex items-start gap-3 min-w-0">
                        <div className="mt-0.5 w-8 h-8 rounded-lg bg-violet-500/10 border border-violet-500/20 flex items-center justify-center shrink-0">
                          <Layers className="w-4 h-4 text-violet-400" />
                        </div>
                        <div className="min-w-0">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="text-sm font-semibold text-white">{group.alias}</span>
                            <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] bg-violet-500/10 border border-violet-500/20 text-violet-300 font-mono">
                              {group.logicalId}
                            </span>
                          </div>
                          {group.description && (
                            <p className="text-[11px] text-gray-600 mt-0.5 truncate max-w-xs">{group.description}</p>
                          )}
                          <div className="flex items-center gap-1 mt-1">
                            <Users className="w-2.5 h-2.5 text-gray-500" />
                            <span className="text-[10px] text-gray-500">
                              {group.memberCount} certificate{group.memberCount !== 1 ? 's' : ''}
                            </span>
                          </div>
                        </div>
                      </div>
                      <div className="flex flex-col items-end gap-1.5 shrink-0">
                        <div className="flex items-center gap-1">
                          {expiryIcon(group.expiryHealthStatus)}
                          <span className="text-[11px] text-gray-500">
                            {group.expiryHealthStatus === 'VALID' ? 'All valid' :
                             group.expiryHealthStatus === 'EXPIRING_SOON' ? 'Expiring soon' : 'Expired'}
                          </span>
                        </div>
                        {isLoaded ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium bg-violet-500/10 border border-violet-500/20 text-violet-300">
                            <CheckCircle className="w-2.5 h-2.5" /> loaded in gateway
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium bg-gray-500/10 border border-gray-500/20 text-gray-500">
                            <Clock className="w-2.5 h-2.5" /> pending load
                          </span>
                        )}
                      </div>
                    </div>

                    <div className="mt-3 pt-3 border-t border-white/[0.04] flex items-center gap-2 flex-wrap">
                      <Link className="w-3.5 h-3.5 text-indigo-400 shrink-0" />
                      <span className="text-xs text-gray-400">Gateway TLS key:</span>
                      <code className="text-xs text-indigo-300 font-mono bg-indigo-500/10 px-2 py-0.5 rounded">{group.logicalId}</code>
                      {liveEntry?.notAfter && (
                        <span className="text-[10px] text-gray-500">
                          expires {new Date(liveEntry.notAfter).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })}
                        </span>
                      )}
                      {liveEntry?.source && (
                        <span className="ml-auto text-[10px] text-gray-600 font-mono">{liveEntry.source}</span>
                      )}
                    </div>

                    {group.members && group.members.length > 0 && (
                      <div className="mt-3 pt-3 border-t border-white/[0.04] space-y-1.5">
                        {group.members.filter(m => m.status === 'ACTIVE').map(cert => (
                          <div key={cert.id} className="flex items-center gap-2 text-xs text-gray-500">
                            <ShieldCheck className="w-3 h-3 text-indigo-400/60 shrink-0" />
                            <span className="text-white/70 truncate">{cert.alias}</span>
                            {cert.memberAlias && (
                              <span className="text-[10px] text-violet-400 bg-violet-500/10 px-1.5 py-0.5 rounded font-medium">{cert.memberAlias}</span>
                            )}
                            {cert.hasPrivateKey && (
                              <span className="inline-flex items-center gap-0.5 text-[10px] text-emerald-400">
                                <Key className="w-2.5 h-2.5" /> key
                              </span>
                            )}
                            {cert.notAfter && (
                              <span className="ml-auto text-[10px] text-gray-600 shrink-0">
                                exp. {new Date(cert.notAfter).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })}
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>

      {/* ── Live Registry ─────────────────────────────────────────────────────── */}
      <div className="mb-8">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <Server className="w-4 h-4 text-gray-400" />
            <span className="text-sm font-semibold text-white">Live Gateway Registry</span>
            <span className="text-[10px] text-gray-500 px-2 py-0.5 rounded-full bg-white/[0.04] border border-white/[0.06]">in-memory</span>
          </div>
          <button
            onClick={() => refetchLive()}
            disabled={liveLoading}
            className="flex items-center gap-1 text-xs text-gray-400 hover:text-white px-2 py-1 rounded-md hover:bg-white/[0.05] transition-colors"
          >
            <RotateCcw className={cn('w-3 h-3', liveLoading && 'animate-spin')} /> Refresh
          </button>
        </div>

        {liveLoading ? (
          <div className="flex items-center gap-2 py-4 text-sm text-gray-500">
            <div className="w-4 h-4 border border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" /> Fetching live registry…
          </div>
        ) : liveEntries && Object.keys(liveEntries).length > 0 ? (
          <div className="rounded-xl border border-white/[0.06] overflow-hidden">
            <table className="w-full text-xs">
              <thead>
                <tr className="bg-white/[0.02] border-b border-white/[0.06]">
                  {['Logical ID', 'Fingerprint', 'Expiry', 'Source', 'Status'].map(h => (
                    <th key={h} className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-wider">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-white/[0.04]">
                {Object.entries(liveEntries).map(([logicalId, entry]) => (
                  <tr key={logicalId} className="hover:bg-white/[0.02] transition-colors">
                    <td className="px-4 py-2.5 font-mono text-white">{logicalId}</td>
                    <td className="px-4 py-2.5 font-mono text-gray-500 truncate max-w-[160px]" title={entry.fingerprint}>
                      {entry.fingerprint ? entry.fingerprint.slice(0, 20) + '…' : '—'}
                    </td>
                    <td className="px-4 py-2.5 text-gray-400">
                      {entry.notAfter ? new Date(entry.notAfter).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }) : '—'}
                    </td>
                    <td className="px-4 py-2.5 font-mono text-gray-500 truncate max-w-[120px]">{entry.source ?? '—'}</td>
                    <td className="px-4 py-2.5">
                      {entry.status ? (
                        <span className={cn(
                          'inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-medium',
                          entry.status === 'VALID'         ? 'text-emerald-400 bg-emerald-400/10' :
                          entry.status === 'EXPIRING_SOON' ? 'text-amber-400 bg-amber-400/10' :
                          'text-red-400 bg-red-400/10',
                        )}>
                          {expiryIcon(entry.status)}
                          {entry.status.replace('_', ' ')}
                        </span>
                      ) : <span className="text-gray-600">—</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState title="No certificates loaded in gateway registry" />
        )}
      </div>

      {/* ── File Sources ──────────────────────────────────────────────────────── */}
      <div className="mb-8">
        <div className="flex items-center justify-between mb-3">
          <span className="text-sm font-semibold text-white">Certificate File Sources</span>
          <button
            onClick={() => setCfg(p => ({ ...p, fileSources: [...p.fileSources, { logicalId: '', certificatePath: '', watchForChanges: true }] }))}
            className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300 transition-colors"
          >
            <Plus className="w-3.5 h-3.5" /> Add Source
          </button>
        </div>

        {cfg.fileSources.length === 0 ? (
          <EmptyState title="No file sources configured" description="File-based certificates load from disk on startup" />
        ) : (
          <div className="space-y-3">
            {cfg.fileSources.map((src, i) => (
              <div key={i} className="bg-white/[0.03] rounded-xl border border-white/[0.05] p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Server className="w-4 h-4 text-gray-400" />
                    <span className="text-sm text-white font-mono">{src.logicalId || '(new)'}</span>
                    {src.status && (
                      <span className={cn('text-xs font-medium', certStatusColor[src.status] ?? 'text-gray-400')}>{src.status}</span>
                    )}
                  </div>
                  <button onClick={() => setCfg(p => ({ ...p, fileSources: p.fileSources.filter((_, j) => j !== i) }))}
                    className="p-1 text-red-400/70 hover:text-red-400 hover:bg-red-400/10 rounded transition-colors">
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Logical ID">
                    <input value={src.logicalId}
                      onChange={e => { const s = [...cfg.fileSources]; s[i] = { ...s[i], logicalId: e.target.value }; setCfg(p => ({ ...p, fileSources: s })) }}
                      className={monoInputCls} />
                  </Field>
                  <Field label="Certificate Path">
                    <input value={src.certificatePath}
                      onChange={e => { const s = [...cfg.fileSources]; s[i] = { ...s[i], certificatePath: e.target.value }; setCfg(p => ({ ...p, fileSources: s })) }}
                      placeholder="/etc/certs/client.cer" className={monoInputCls} />
                  </Field>
                </div>
                <ToggleRow
                  label="Watch for Changes"
                  description="Hot-reload when this file changes on disk"
                  checked={src.watchForChanges}
                  onChange={v => { const s = [...cfg.fileSources]; s[i] = { ...s[i], watchForChanges: v }; setCfg(p => ({ ...p, fileSources: s })) }}
                />
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── Directory Sources ──────────────────────────────────────────────────── */}
      <div className="mb-2">
        <div className="flex items-center justify-between mb-3">
          <span className="text-sm font-semibold text-white">Directory Sources</span>
          <button
            onClick={() => setCfg(p => ({ ...p, directorySources: [...(p.directorySources ?? []), { directoryPath: '', watchForChanges: true }] }))}
            className="flex items-center gap-1 text-xs text-indigo-400 hover:text-indigo-300 transition-colors"
          >
            <Plus className="w-3.5 h-3.5" /> Add Directory
          </button>
        </div>
        <p className="text-xs text-gray-500 mb-3">
          Every <code className="bg-white/5 px-1 rounded">.pem</code>,{' '}
          <code className="bg-white/5 px-1 rounded">.cer</code>, and{' '}
          <code className="bg-white/5 px-1 rounded">.crt</code> file in these directories is automatically loaded.
          The filename stem is used as the logical ID.
        </p>

        {(cfg.directorySources ?? []).length === 0 ? (
          <EmptyState title="No directory sources configured" />
        ) : (
          <div className="space-y-3">
            {(cfg.directorySources ?? []).map((src, i) => (
              <div key={i} className="bg-white/[0.03] rounded-xl border border-white/[0.05] p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <FolderOpen className="w-4 h-4 text-gray-400" />
                    <span className="text-sm text-white font-mono">{src.directoryPath || '(new)'}</span>
                  </div>
                  <button onClick={() => setCfg(p => ({ ...p, directorySources: (p.directorySources ?? []).filter((_, j) => j !== i) }))}
                    className="p-1 text-red-400/70 hover:text-red-400 hover:bg-red-400/10 rounded transition-colors">
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
                <Field label="Directory Path">
                  <input value={src.directoryPath}
                    onChange={e => { const s = [...(cfg.directorySources ?? [])]; s[i] = { ...s[i], directoryPath: e.target.value }; setCfg(p => ({ ...p, directorySources: s })) }}
                    placeholder="/etc/gateway/certs/" className={monoInputCls} />
                </Field>
                <ToggleRow
                  label="Watch for Changes"
                  description="Hot-reload when files in this directory change"
                  checked={src.watchForChanges}
                  onChange={v => { const s = [...(cfg.directorySources ?? [])]; s[i] = { ...s[i], watchForChanges: v }; setCfg(p => ({ ...p, directorySources: s })) }}
                />
              </div>
            ))}
          </div>
        )}
      </div>

      <SaveBar onSave={() => onSave(cfg)} isPending={isPending} dirty={dirty} />
    </div>
  )
}

