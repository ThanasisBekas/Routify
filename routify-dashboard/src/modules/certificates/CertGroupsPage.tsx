import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Layers,
  Plus,
  Trash2,
  Loader2,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  Archive,
  ShieldCheck,
  Users,
  Edit2,
  Upload,
  UserMinus,
  RotateCcw,
  Server,
  ChevronRight,
} from 'lucide-react'
import { certVaultApi } from '../../api/certVaultApi'
import type { CertGroupDto, CertificateDto, CertExpiryStatus } from '../../types'
import { cn } from '../../lib/utils'
import { useAuthStore } from '../../store/authStore'
import CertGroupFormModal from './CertGroupFormModal'
import CertUploadModal from './CertUploadModal'
import CertDetailModal from './CertDetailModal'

// ─── Helpers ──────────────────────────────────────────────────────────────────

const EXPIRY_ICON: Record<CertExpiryStatus, React.ReactNode> = {
  VALID:         <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />,
  EXPIRING_SOON: <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />,
  EXPIRED:       <XCircle className="w-3.5 h-3.5 text-red-400" />,
}

const STATUS_COLOR: Record<string, string> = {
  ACTIVE:  'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
  REVOKED: 'text-red-400    bg-red-400/10    border-red-400/20',
  EXPIRED: 'text-orange-400 bg-orange-400/10 border-orange-400/20',
  DELETED: 'text-gray-500   bg-gray-500/10   border-gray-500/20',
}

function formatDate(iso?: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
}

// ─── Group Card ───────────────────────────────────────────────────────────────

function GroupCard({
  group, isSelected, onSelect, onEdit, onArchive, onDelete, onUpload, isArchiving, isDeleting,
}: {
  group: CertGroupDto; isSelected: boolean
  onSelect: () => void; onEdit: () => void; onArchive: () => void
  onDelete: () => void; onUpload: () => void
  isArchiving: boolean; isDeleting: boolean
}) {
  return (
    <div
      onClick={onSelect}
      className={cn(
        'relative rounded-xl border p-4 cursor-pointer transition-all group',
        isSelected
          ? 'border-violet-500/50 bg-violet-500/5 shadow-lg shadow-violet-500/5'
          : 'border-white/6 bg-white/2 hover:border-white/12 hover:bg-white/3',
        group.status === 'ARCHIVED' && 'opacity-60'
      )}
    >
      {/* Top-right actions */}
      <div className="absolute top-2.5 right-2.5 flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
        {group.status === 'ACTIVE' && (
          <button
            onClick={e => { e.stopPropagation(); onUpload() }}
            title="Upload certificate to this group"
            className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-indigo-400 transition-colors"
          >
            <Upload className="w-3 h-3" />
          </button>
        )}
        <button onClick={e => { e.stopPropagation(); onEdit() }} title="Edit group"
          className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-white transition-colors">
          <Edit2 className="w-3 h-3" />
        </button>
        {group.status === 'ACTIVE' && (
          <button onClick={e => { e.stopPropagation(); onArchive() }} title="Archive group"
            disabled={isArchiving}
            className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-amber-400 transition-colors disabled:opacity-50">
            {isArchiving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Archive className="w-3 h-3" />}
          </button>
        )}
        <button onClick={e => { e.stopPropagation(); onDelete() }} title="Delete group"
          disabled={isDeleting}
          className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-red-400 transition-colors disabled:opacity-50">
          {isDeleting ? <Loader2 className="w-3 h-3 animate-spin" /> : <Trash2 className="w-3 h-3" />}
        </button>
      </div>

      {/* Icon + title */}
      <div className="flex items-start gap-3 mb-3 pr-16">
        <div className="w-9 h-9 rounded-lg bg-violet-500/10 border border-violet-500/20 flex items-center justify-center shrink-0 mt-0.5">
          <Layers className="w-4 h-4 text-violet-400" />
        </div>
        <div className="min-w-0">
          <p className="font-semibold text-sm text-white truncate">{group.alias}</p>
          {/* Gateway TLS key badge */}
          <div className="flex items-center gap-1 mt-0.5">
            <Server className="w-2.5 h-2.5 text-indigo-400 shrink-0" />
            <span className="text-[10px] text-indigo-300 font-mono truncate">{group.logicalId}</span>
          </div>
        </div>
      </div>

      {group.description && (
        <p className="text-xs text-gray-500 mb-3 line-clamp-1">{group.description}</p>
      )}

      {/* Stats */}
      <div className="flex items-center gap-3 text-xs text-gray-500">
        <div className="flex items-center gap-1">
          <Users className="w-3 h-3" />
          <span>{group.memberCount}</span>
        </div>
        <div className="flex items-center gap-1">
          {EXPIRY_ICON[group.expiryHealthStatus]}
          <span className={cn(
            group.expiryHealthStatus === 'EXPIRED'       ? 'text-red-400' :
            group.expiryHealthStatus === 'EXPIRING_SOON' ? 'text-amber-400' : 'text-emerald-400'
          )}>
            {group.expiryHealthStatus === 'VALID' ? 'Valid' :
             group.expiryHealthStatus === 'EXPIRING_SOON' ? 'Expiring soon' : 'Expired'}
          </span>
        </div>
        {group.status === 'ARCHIVED' && (
          <span className="text-[10px] text-gray-500 bg-gray-500/10 border border-gray-500/20 px-1.5 py-0.5 rounded-full ml-auto">ARCHIVED</span>
        )}
        <span className="ml-auto text-gray-600">{formatDate(group.createdAt)}</span>
      </div>

      {isSelected && <ChevronRight className="absolute right-3 bottom-3.5 w-3 h-3 text-violet-400" />}
    </div>
  )
}

// ─── Member Row ───────────────────────────────────────────────────────────────

function MemberRow({ cert, onRevoke, onDelete, onView, onRemove, isRevoking, isDeleting, isRemoving }: {
  cert: CertificateDto
  onRevoke: () => void; onDelete: () => void; onView: () => void; onRemove: () => void
  isRevoking: boolean; isDeleting: boolean; isRemoving: boolean
}) {
  return (
    <div className="flex items-center gap-3 py-2.5 px-3 rounded-lg bg-white/2 border border-white/4 group hover:bg-white/3 transition-colors">
      <div className="w-7 h-7 rounded-md bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center shrink-0">
        <ShieldCheck className="w-3.5 h-3.5 text-indigo-400" />
      </div>
      <div className="min-w-0 flex-1">
        <button onClick={onView} className="text-sm text-white font-medium hover:text-indigo-300 transition-colors text-left truncate max-w-48">
          {cert.alias}
        </button>
        <div className="flex items-center gap-2 mt-0.5">
          {cert.memberAlias && (
            <span className="text-[10px] font-medium text-violet-400 bg-violet-500/10 px-1.5 py-0.5 rounded">{cert.memberAlias}</span>
          )}
          {cert.subjectDn && (
            <span className="text-[10px] text-gray-600 truncate">{cert.subjectDn.replace(/^.*?CN=/, '')}</span>
          )}
        </div>
      </div>
      <div className="flex items-center gap-2 shrink-0">
        <span className={cn('text-[10px] font-medium px-1.5 py-0.5 rounded-full border', STATUS_COLOR[cert.status])}>
          {cert.status}
        </span>
        <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
          {cert.status === 'ACTIVE' && (
            <button onClick={onRevoke} disabled={isRevoking} title="Revoke"
              className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-amber-400 transition-colors disabled:opacity-50">
              {isRevoking ? <Loader2 className="w-3 h-3 animate-spin" /> : <RotateCcw className="w-3 h-3" />}
            </button>
          )}
          <button onClick={onRemove} disabled={isRemoving} title="Remove from group"
            className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-orange-400 transition-colors disabled:opacity-50">
            {isRemoving ? <Loader2 className="w-3 h-3 animate-spin" /> : <UserMinus className="w-3 h-3" />}
          </button>
          <button onClick={onDelete} disabled={isDeleting} title="Delete certificate"
            className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-red-400 transition-colors disabled:opacity-50">
            {isDeleting ? <Loader2 className="w-3 h-3 animate-spin" /> : <Trash2 className="w-3 h-3" />}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function CertGroupsPage() {
  const qc       = useQueryClient()
  const { user } = useAuthStore()
  const tenantId = user?.tenantId ?? ''

  const [selectedGroup,  setSelectedGroup]  = useState<CertGroupDto | null>(null)
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [editGroup,      setEditGroup]      = useState<CertGroupDto | null>(null)
  /** Group to upload INTO (triggers CertUploadModal with preselected group) */
  const [uploadIntoGroup, setUploadIntoGroup] = useState<CertGroupDto | null>(null)
  const [detailCert,      setDetailCert]      = useState<CertificateDto | null>(null)
  const [statusFilter,    setStatusFilter]    = useState<string>('')

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['cert-groups'] })
    qc.invalidateQueries({ queryKey: ['cert-group-detail'] })
    qc.invalidateQueries({ queryKey: ['cert-stats'] })
  }

  const { data: pageData, isLoading } = useQuery({
    queryKey: ['cert-groups', tenantId, statusFilter],
    queryFn:  () => certVaultApi.listGroups({ tenantId, status: statusFilter || undefined, size: 100 }),
    enabled:  !!tenantId,
  })

  const { data: groupDetail, isLoading: isLoadingDetail } = useQuery({
    queryKey: ['cert-group-detail', selectedGroup?.id, tenantId],
    queryFn:  () => certVaultApi.getGroup(selectedGroup!.id, tenantId),
    enabled:  !!selectedGroup && !!tenantId,
  })

  const archiveMutation = useMutation({
    mutationFn: (id: string) => certVaultApi.archiveGroup(id, tenantId),
    onSuccess:  () => { invalidate(); setSelectedGroup(null) },
  })

  const deleteGroupMutation = useMutation({
    mutationFn: (id: string) => certVaultApi.deleteGroup(id, tenantId),
    onSuccess:  () => { invalidate(); setSelectedGroup(null) },
  })

  const revokeMutation = useMutation({
    mutationFn: (certId: string) => certVaultApi.revokeCertificate(certId, tenantId),
    onSuccess:  () => invalidate(),
  })

  const deleteCertMutation = useMutation({
    mutationFn: (certId: string) => certVaultApi.deleteCertificate(certId, tenantId),
    onSuccess:  () => invalidate(),
  })

  const removeMemberMutation = useMutation({
    mutationFn: ({ groupId, certId }: { groupId: string; certId: string }) =>
      certVaultApi.removeMemberFromGroup(groupId, certId, tenantId),
    onSuccess: () => invalidate(),
  })

  const groups = pageData?.content ?? []
  const detail = groupDetail as CertGroupDto | undefined

  return (
    <div className="flex flex-col h-full animate-fade-in">
      {/* ── Header ── */}
      <div className="flex items-center justify-between px-6 py-5 border-b border-white/6 bg-[#0c0e14]">
        <div>
          <h1 className="text-lg font-bold text-white tracking-tight mb-1 flex items-center gap-2">
            <ShieldCheck className="w-5 h-5 text-indigo-400" />
            Certificate Vault
          </h1>
          <p className="text-sm text-gray-500">
            Certificates are organised in groups — the group's logical ID is the stable gateway TLS key
          </p>
        </div>
        <div className="flex items-center gap-2">
          {selectedGroup?.status === 'ACTIVE' && (
            <button
              onClick={() => setUploadIntoGroup(selectedGroup)}
              className="flex items-center gap-2 px-3.5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20"
            >
              <Upload className="w-4 h-4" />
              Upload Certificate
            </button>
          )}
          <button
            onClick={() => setShowCreateForm(true)}
            className="flex items-center gap-2 px-3.5 py-2 bg-violet-600 hover:bg-violet-500 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-violet-500/20"
          >
            <Plus className="w-4 h-4" />
            New Group
          </button>
        </div>
      </div>

      {/* ── Filter bar ── */}
      <div className="flex items-center gap-2 px-6 py-2.5 border-b border-white/6 bg-[#0c0e14]">
        <span className="text-xs text-gray-500">Groups:</span>
        {(['', 'ACTIVE', 'ARCHIVED'] as const).map(s => (
          <button key={s} onClick={() => setStatusFilter(s)}
            className={cn(
              'px-3 py-1 rounded-full text-xs font-medium border transition-all',
              statusFilter === s
                ? 'bg-violet-500/20 text-violet-300 border-violet-500/40'
                : 'bg-transparent text-gray-500 border-white/8 hover:text-gray-300'
            )}>
            {s === '' ? 'All' : s}
          </button>
        ))}
      </div>

      {/* ── Split panel ── */}
      <div className="flex flex-1 overflow-hidden">

        {/* Left: group list */}
        <div className="w-80 shrink-0 border-r border-white/6 overflow-y-auto p-3.5 space-y-2">
          {isLoading ? (
            <div className="flex items-center justify-center py-12">
              <div className="w-6 h-6 border-2 border-violet-500/30 border-t-violet-500 rounded-full animate-spin" />
            </div>
          ) : groups.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 gap-4 text-center px-4">
              <div className="w-14 h-14 rounded-2xl bg-white/3 border border-white/6 flex items-center justify-center">
                <Layers className="w-6 h-6 text-gray-600" />
              </div>
              <div>
                <p className="text-sm font-medium text-gray-300 mb-1">No certificate groups yet</p>
                <p className="text-xs text-gray-600">
                  Create a group first — you'll upload certificates into it.
                  The group's logical ID becomes the gateway TLS key.
                </p>
              </div>
              <button
                onClick={() => setShowCreateForm(true)}
                className="flex items-center gap-2 px-3 py-1.5 bg-violet-600/20 hover:bg-violet-600/30 border border-violet-500/30 text-violet-400 text-xs font-medium rounded-lg transition-all"
              >
                <Plus className="w-3.5 h-3.5" />
                Create first group
              </button>
            </div>
          ) : groups.map(group => (
            <GroupCard
              key={group.id}
              group={group}
              isSelected={selectedGroup?.id === group.id}
              onSelect={() => setSelectedGroup(group)}
              onEdit={() => setEditGroup(group)}
              onUpload={() => setUploadIntoGroup(group)}
              onArchive={() => {
                if (window.confirm(`Archive group "${group.alias}"? Certificates will no longer be served by the gateway.`))
                  archiveMutation.mutate(group.id)
              }}
              onDelete={() => {
                if (window.confirm(`Delete group "${group.alias}"? All member certificates will become standalone.`))
                  deleteGroupMutation.mutate(group.id)
              }}
              isArchiving={archiveMutation.isPending && archiveMutation.variables === group.id}
              isDeleting={deleteGroupMutation.isPending && deleteGroupMutation.variables === group.id}
            />
          ))}
        </div>

        {/* Right: group detail */}
        <div className="flex-1 overflow-y-auto">
          {!selectedGroup ? (
            <div className="flex flex-col items-center justify-center h-full gap-3 text-center px-8">
              <Layers className="w-10 h-10 text-gray-700" />
              <div>
                <p className="text-sm text-gray-400 font-medium mb-1">Select a group</p>
                <p className="text-xs text-gray-600">
                  Choose a group to view its members, upload new certificates, or manage rotation
                </p>
              </div>
            </div>
          ) : isLoadingDetail ? (
            <div className="flex items-center justify-center py-20">
              <div className="w-6 h-6 border-2 border-violet-500/30 border-t-violet-500 rounded-full animate-spin" />
            </div>
          ) : detail ? (
            <div className="p-6">
              {/* Group header */}
              <div className="flex items-start justify-between mb-5">
                <div>
                  <div className="flex items-center gap-2 mb-2">
                    <Layers className="w-5 h-5 text-violet-400" />
                    <h2 className="text-base font-bold text-white">{detail.alias}</h2>
                    {detail.status === 'ARCHIVED' && (
                      <span className="text-[10px] font-medium text-gray-500 bg-gray-500/10 border border-gray-500/20 px-1.5 py-0.5 rounded-full">ARCHIVED</span>
                    )}
                  </div>
                  {/* Gateway TLS key */}
                  <div className="flex items-center gap-2">
                    <Server className="w-3.5 h-3.5 text-indigo-400" />
                    <span className="text-xs text-gray-500">Gateway TLS key:</span>
                    <span className="text-sm font-mono text-indigo-300 bg-indigo-500/10 px-2 py-0.5 rounded border border-indigo-500/20">
                      {detail.logicalId}
                    </span>
                  </div>
                  {detail.description && (
                    <p className="text-xs text-gray-500 mt-2">{detail.description}</p>
                  )}
                </div>
                {detail.status === 'ACTIVE' && (
                  <button
                    onClick={() => setUploadIntoGroup(detail)}
                    className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-xs font-medium rounded-lg transition-all shrink-0"
                  >
                    <Upload className="w-3.5 h-3.5" />
                    Upload Certificate
                  </button>
                )}
              </div>

              {/* Members header */}
              <div className="flex items-center gap-2 mb-3">
                <Users className="w-4 h-4 text-gray-500" />
                <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Certificates ({detail.members?.length ?? 0})
                </h3>
                <div className="ml-auto flex items-center gap-1">
                  {EXPIRY_ICON[detail.expiryHealthStatus]}
                  <span className={cn('text-xs',
                    detail.expiryHealthStatus === 'EXPIRED'       ? 'text-red-400' :
                    detail.expiryHealthStatus === 'EXPIRING_SOON' ? 'text-amber-400' : 'text-emerald-400'
                  )}>
                    {detail.expiryHealthStatus === 'VALID' ? 'All valid' :
                     detail.expiryHealthStatus === 'EXPIRING_SOON' ? 'Expiring soon' : 'Has expired'}
                  </span>
                </div>
              </div>

              {/* Member list */}
              {!detail.members || detail.members.length === 0 ? (
                <div className="flex flex-col items-center py-12 gap-3 text-center border border-white/4 rounded-xl bg-white/1">
                  <ShieldCheck className="w-8 h-8 text-gray-700" />
                  <div>
                    <p className="text-sm text-gray-400 mb-1">No certificates in this group</p>
                    <p className="text-xs text-gray-600">
                      Upload a certificate — it will be served by the gateway under{' '}
                      <span className="font-mono text-indigo-300">{detail.logicalId}</span>
                    </p>
                  </div>
                  {detail.status === 'ACTIVE' && (
                    <button
                      onClick={() => setUploadIntoGroup(detail)}
                      className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-xs font-medium rounded-lg transition-all"
                    >
                      <Upload className="w-3.5 h-3.5" />
                      Upload first certificate
                    </button>
                  )}
                </div>
              ) : (
                <div className="space-y-1.5">
                  {detail.members.map(cert => (
                    <MemberRow
                      key={cert.id}
                      cert={cert}
                      onView={() => setDetailCert(cert)}
                      onRevoke={() => {
                        if (window.confirm(`Revoke certificate "${cert.alias}"? This cannot be undone.`))
                          revokeMutation.mutate(cert.id)
                      }}
                      onDelete={() => {
                        if (window.confirm(`Delete certificate "${cert.alias}"?`))
                          deleteCertMutation.mutate(cert.id)
                      }}
                      onRemove={() => {
                        if (window.confirm(`Remove "${cert.alias}" from group "${detail.alias}"?`))
                          removeMemberMutation.mutate({ groupId: detail.id, certId: cert.id })
                      }}
                      isRevoking={revokeMutation.isPending && revokeMutation.variables === cert.id}
                      isDeleting={deleteCertMutation.isPending && deleteCertMutation.variables === cert.id}
                      isRemoving={
                        removeMemberMutation.isPending &&
                        (removeMemberMutation.variables as any)?.certId === cert.id
                      }
                    />
                  ))}
                </div>
              )}

              {/* Rotation hint */}
              {detail.members && detail.members.length > 0 && detail.status === 'ACTIVE' && (
                <div className="mt-5 p-3 rounded-lg bg-violet-500/5 border border-violet-500/10">
                  <p className="text-xs text-violet-300/70 leading-relaxed">
                    <span className="font-semibold text-violet-300">Rotation:</span>{' '}
                    Upload a new certificate here → the gateway immediately serves it under{' '}
                    <code className="text-violet-300">{detail.logicalId}</code> → then revoke or remove the old one.
                    No gateway restart or config change required.
                  </p>
                </div>
              )}
            </div>
          ) : null}
        </div>
      </div>

      {/* ── Modals ── */}
      {showCreateForm && (
        <CertGroupFormModal
          tenantId={tenantId}
          onClose={() => setShowCreateForm(false)}
          onSuccess={() => { setShowCreateForm(false); invalidate() }}
        />
      )}
      {editGroup && (
        <CertGroupFormModal
          tenantId={tenantId}
          group={editGroup}
          onClose={() => setEditGroup(null)}
          onSuccess={() => { setEditGroup(null); invalidate() }}
        />
      )}
      {uploadIntoGroup && (
        <CertUploadModal
          tenantId={tenantId}
          preselectedGroup={uploadIntoGroup}
          onClose={() => setUploadIntoGroup(null)}
          onSuccess={() => {
            setUploadIntoGroup(null)
            invalidate()
          }}
        />
      )}
      {detailCert && (
        <CertDetailModal cert={detailCert} onClose={() => setDetailCert(null)} />
      )}
    </div>
  )
}

