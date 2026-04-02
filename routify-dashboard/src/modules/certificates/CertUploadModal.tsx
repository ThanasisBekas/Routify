import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { certVaultApi } from '../../api/certVaultApi'
import type { CertGroupDto, UploadCertificateRequest } from '../../types'
import { X, Upload, Loader2, AlertCircle, Layers, CheckCircle2, Info } from 'lucide-react'
import { Select } from '../../components/ui/Select'
import { cn } from '../../lib/utils'

interface Props {
  tenantId:          string
  /** Pre-selected group — if provided the group picker is hidden */
  preselectedGroup?: CertGroupDto
  onClose:           () => void
  onSuccess:         () => void
}

export default function CertUploadModal({ tenantId, preselectedGroup, onClose, onSuccess }: Props) {
  const [selectedGroupId, setSelectedGroupId] = useState<string>(preselectedGroup?.id ?? '')
  const [form, setForm] = useState<Omit<UploadCertificateRequest, 'groupId'>>({
    memberAlias: '',
    alias:       '',
    description: '',
    format:      'PEM',
    certPem:     '',
    privateKey:  '',
  })
  const [error, setError] = useState<string>('')

  const { data: groupsPage } = useQuery({
    queryKey: ['cert-groups', tenantId, 'ACTIVE'],
    queryFn:  () => certVaultApi.listGroups({ tenantId, status: 'ACTIVE', size: 100 }),
    enabled:  !!tenantId && !preselectedGroup,
  })
  const availableGroups: CertGroupDto[] = groupsPage?.content ?? []
  const selectedGroup = preselectedGroup ?? availableGroups.find(g => g.id === selectedGroupId)

  const uploadMutation = useMutation({
    mutationFn: () => certVaultApi.uploadCertificate(tenantId, {
      groupId:     selectedGroupId,
      memberAlias: form.memberAlias?.trim() || undefined,
      alias:       form.alias,
      description: form.description || undefined,
      format:      form.format,
      certPem:     form.certPem,
      privateKey:  form.privateKey || undefined,
    } as UploadCertificateRequest),
    onSuccess: () => onSuccess(),
    onError:   (e: any) => setError(e?.response?.data?.detail ?? e.message ?? 'Upload failed'),
  })

  const set = (key: keyof typeof form) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
      setForm(f => ({ ...f, [key]: e.target.value }))

  const handleFileRead = (key: 'certPem' | 'privateKey') => async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const text = await file.text()
    setForm(f => ({ ...f, [key]: text }))
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!selectedGroupId)     { setError('You must select a certificate group first'); return }
    if (!form.alias.trim())   { setError('Display name is required'); return }
    if (!form.certPem.trim()) { setError('Certificate PEM is required'); return }
    uploadMutation.mutate()
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative w-full max-w-2xl bg-[#0f1117] border border-white/8 rounded-2xl shadow-2xl overflow-hidden animate-scale-in">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/6">
          <div>
            <h2 className="text-base font-bold text-white flex items-center gap-2">
              <Upload className="w-4 h-4 text-indigo-400" />
              Upload Certificate
            </h2>
            <p className="text-xs text-gray-500 mt-0.5">
              {selectedGroup
                ? <>Adding to group <span className="text-violet-300 font-mono">{selectedGroup.logicalId}</span></>
                : 'Select a group, then upload a PEM or PKCS12 certificate'}
            </p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-white/6 text-gray-500 hover:text-white transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4 overflow-y-auto max-h-[75vh]">
          {error && (
            <div className="flex items-start gap-2 p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm">
              <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
              {error}
            </div>
          )}

          {/* ── Group section ── */}
          {preselectedGroup ? (
            <div className="flex items-center gap-3 p-3 rounded-lg bg-violet-500/5 border border-violet-500/20">
              <div className="w-8 h-8 rounded-lg bg-violet-500/10 border border-violet-500/20 flex items-center justify-center shrink-0">
                <Layers className="w-4 h-4 text-violet-400" />
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-semibold text-white">{preselectedGroup.alias}</p>
                <p className="text-xs text-violet-300 font-mono">{preselectedGroup.logicalId}</p>
              </div>
              <CheckCircle2 className="w-4 h-4 text-violet-400 shrink-0" />
            </div>
          ) : (
            <div>
              <label className="block text-xs font-semibold text-gray-400 mb-2">
                Certificate Group <span className="text-red-400">*</span>
              </label>
              {availableGroups.length === 0 ? (
                <div className="flex items-start gap-2 p-3 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-400 text-xs">
                  <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                  <span>No active certificate groups found. Create a group first from the Certificate Vault page.</span>
                </div>
              ) : (
                <div className="flex flex-col gap-1.5 max-h-36 overflow-y-auto pr-1">
                  {availableGroups.map(group => (
                    <button
                      key={group.id}
                      type="button"
                      onClick={() => setSelectedGroupId(group.id)}
                      className={cn(
                        'flex items-center gap-2.5 px-3 py-2 rounded-lg border text-xs transition-all text-left',
                        selectedGroupId === group.id
                          ? 'bg-violet-500/20 border-violet-500/40 text-violet-300'
                          : 'bg-white/3 border-white/8 text-gray-400 hover:text-white hover:border-white/20'
                      )}
                    >
                      <div className="w-6 h-6 rounded-md bg-violet-500/10 border border-violet-500/20 flex items-center justify-center shrink-0">
                        {selectedGroupId === group.id
                          ? <CheckCircle2 className="w-3 h-3 text-violet-400" />
                          : <Layers className="w-3 h-3 text-violet-400" />
                        }
                      </div>
                      <div className="min-w-0 flex-1">
                        <span className="font-medium text-white">{group.alias}</span>
                        <span className="text-gray-500 font-mono ml-2 text-[10px]">{group.logicalId}</span>
                      </div>
                      <span className="text-[10px] text-gray-600 shrink-0">{group.memberCount} member{group.memberCount !== 1 ? 's' : ''}</span>
                    </button>
                  ))}
                </div>
              )}
              <p className="text-[11px] text-gray-600 mt-1.5">
                The group's logical ID is the stable gateway TLS key — all certs in the group are served under it.
              </p>
            </div>
          )}

          {/* Info banner when group is chosen */}
          {selectedGroup && (
            <div className="flex items-start gap-2 p-3 rounded-lg bg-indigo-500/5 border border-indigo-500/15 text-xs text-indigo-300/80">
              <Info className="w-3.5 h-3.5 shrink-0 mt-0.5 text-indigo-400" />
              <span>
                This certificate will be served by the gateway under{' '}
                <code className="text-indigo-300 font-mono">{selectedGroup.logicalId}</code>.
                No additional gateway mapping step is required.
              </span>
            </div>
          )}

          {/* Member alias */}
          <div>
            <label className="block text-xs font-semibold text-gray-400 mb-1.5">
              Member Alias <span className="text-gray-600">(optional)</span>
            </label>
            <input
              type="text"
              value={form.memberAlias}
              onChange={set('memberAlias')}
              placeholder="e.g. primary, backup-2025, ecdsa-leaf"
              className="w-full px-3 py-2 bg-white/4 border border-white/8 rounded-lg text-sm text-white font-mono placeholder-gray-600 focus:outline-none focus:border-violet-500/50 focus:ring-1 focus:ring-violet-500/20"
            />
            <p className="text-[11px] text-gray-600 mt-1">Short label to distinguish this cert within the group (e.g. "primary", "backup-2025")</p>
          </div>

          {/* Display name + format */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-semibold text-gray-400 mb-1.5">
                Display Name <span className="text-red-400">*</span>
              </label>
              <input
                type="text"
                value={form.alias}
                onChange={set('alias')}
                placeholder="My API Certificate 2025"
                className="w-full px-3 py-2 bg-white/4 border border-white/8 rounded-lg text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500/50 focus:ring-1 focus:ring-indigo-500/20"
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-gray-400 mb-1.5">Format</label>
              <Select
                value={form.format}
                onChange={v => setForm(f => ({ ...f, format: v as 'PEM' | 'PKCS12' }))}
                options={[
                  { value: 'PEM',    label: 'PEM', description: 'Privacy-Enhanced Mail — most common' },
                  { value: 'PKCS12', label: 'PKCS12 / PFX', description: 'Binary format, common in enterprise environments' },
                ]}
              />
            </div>
          </div>

          {/* Description */}
          <div>
            <label className="block text-xs font-semibold text-gray-400 mb-1.5">Description</label>
            <input
              type="text"
              value={form.description}
              onChange={set('description')}
              placeholder="Optional description"
              className="w-full px-3 py-2 bg-white/4 border border-white/8 rounded-lg text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500/50 focus:ring-1 focus:ring-indigo-500/20"
            />
          </div>

          {/* Certificate PEM */}
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className="block text-xs font-semibold text-gray-400">
                Certificate {form.format === 'PEM' ? '(PEM chain)' : '(base64-encoded PKCS12)'} <span className="text-red-400">*</span>
              </label>
              <label className="flex items-center gap-1.5 px-2 py-1 rounded-md bg-white/4 border border-white/8 text-xs text-gray-400 hover:text-white cursor-pointer transition-colors">
                <Upload className="w-3 h-3" />
                Browse file
                <input type="file" accept=".pem,.crt,.cer,.pfx,.p12" className="hidden" onChange={handleFileRead('certPem')} />
              </label>
            </div>
            <textarea
              value={form.certPem}
              onChange={set('certPem')}
              placeholder={form.format === 'PEM'
                ? '-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----'
                : 'Paste base64-encoded PKCS12 content'}
              rows={6}
              className="w-full px-3 py-2 bg-white/4 border border-white/8 rounded-lg text-xs text-white font-mono placeholder-gray-600 focus:outline-none focus:border-indigo-500/50 focus:ring-1 focus:ring-indigo-500/20 resize-none"
            />
          </div>

          {/* Private Key */}
          {form.format === 'PEM' && (
            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label className="block text-xs font-semibold text-gray-400">Private Key (optional)</label>
                <label className="flex items-center gap-1.5 px-2 py-1 rounded-md bg-white/4 border border-white/8 text-xs text-gray-400 hover:text-white cursor-pointer transition-colors">
                  <Upload className="w-3 h-3" />
                  Browse file
                  <input type="file" accept=".pem,.key" className="hidden" onChange={handleFileRead('privateKey')} />
                </label>
              </div>
              <textarea
                value={form.privateKey}
                onChange={set('privateKey')}
                placeholder="-----BEGIN PRIVATE KEY-----&#10;MIIEv...&#10;-----END PRIVATE KEY-----"
                rows={4}
                className="w-full px-3 py-2 bg-white/4 border border-white/8 rounded-lg text-xs text-white font-mono placeholder-gray-600 focus:outline-none focus:border-indigo-500/50 focus:ring-1 focus:ring-indigo-500/20 resize-none"
              />
              <p className="text-[11px] text-gray-600 mt-1">Private key is stored AES-256-GCM encrypted at rest</p>
            </div>
          )}
        </form>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-white/6 bg-[#0c0e14]">
          <button type="button" onClick={onClose} className="px-4 py-2 rounded-lg text-sm text-gray-400 hover:text-white hover:bg-white/4 transition-colors">
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={uploadMutation.isPending || (!selectedGroupId)}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-lg transition-all disabled:opacity-60"
          >
            {uploadMutation.isPending
              ? <><Loader2 className="w-4 h-4 animate-spin" /> Uploading…</>
              : <><Upload className="w-4 h-4" /> Upload Certificate</>
            }
          </button>
        </div>
      </div>
    </div>
  )
}
