import { useState, useCallback } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
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
  RotateCcw,
  Server,
  ChevronRight,
  Copy,
  Check,
  Clock,
  ShieldAlert,
  TrendingDown,
  Info,
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
  VALID: <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />,
  EXPIRING_SOON: <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />,
  EXPIRED: <XCircle className="w-3.5 h-3.5 text-red-400" />,
}

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
  REVOKED: 'text-red-400    bg-red-400/10    border-red-400/20',
  EXPIRED: 'text-orange-400 bg-orange-400/10 border-orange-400/20',
  DELETED: 'text-gray-500   bg-gray-500/10   border-gray-500/20',
}

function formatDate(iso?: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
}

/** Returns days until expiry (negative = already expired). Returns null if no date. */
function daysUntilExpiry(iso?: string): number | null {
  if (!iso) return null
  return Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000)
}

function ExpiryBadge({ notAfter, expiryStatus }: { notAfter?: string; expiryStatus: CertExpiryStatus }) {
  const days = daysUntilExpiry(notAfter)
  if (days === null) return null

  if (expiryStatus === 'EXPIRED') {
    return (
      <span className="inline-flex items-center gap-1 text-[10px] text-red-400 bg-red-400/10 border border-red-400/20 px-1.5 py-0.5 rounded-full font-medium">
        <XCircle className="w-2.5 h-2.5" /> Expired
      </span>
    )
  }
  if (expiryStatus === 'EXPIRING_SOON') {
    return (
      <span className="inline-flex items-center gap-1 text-[10px] text-amber-400 bg-amber-400/10 border border-amber-400/20 px-1.5 py-0.5 rounded-full font-medium">
        <Clock className="w-2.5 h-2.5" /> {days}d left
      </span>
    )
  }
  return <span className="text-[10px] text-gray-600">{formatDate(notAfter)}</span>
}

// ─── CopyButton ───────────────────────────────────────────────────────────────

function CopyButton({ text, className }: { text: string; className?: string }) {
  const [copied, setCopied] = useState(false)
  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation()
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1800)
    })
  }
  return (
    <button
      onClick={handleCopy}
      title={copied ? 'Copied!' : `Copy "${text}"`}
      className={cn(
        'p-1 rounded-md transition-all',
        copied ? 'text-emerald-400' : 'text-gray-600 hover:text-gray-300 hover:bg-white/6',
        className,
      )}
    >
      {copied ? <Check className="w-3 h-3" /> : <Copy className="w-3 h-3" />}
    </button>
  )
}

// ─── Inline Confirm Button ─────────────────────────────────────────────────────
// Replaces window.confirm() with a two-step inline confirmation

function ConfirmButton({
  onConfirm,
  disabled,
  isLoading,
  title,
  icon: Icon,
  hoverColor,
  confirmIcon: ConfirmIcon,
  confirmLabel,
}: {
  onConfirm: () => void
  disabled?: boolean
  isLoading?: boolean
  title: string
  icon: React.ComponentType<{ className?: string }>
  hoverColor: string
  confirmIcon?: React.ComponentType<{ className?: string }>
  confirmLabel?: string
}) {
  const [confirming, setConfirming] = useState(false)

  if (confirming) {
    return (
      <span className="flex items-center gap-1">
        <button
          onClick={(e) => {
            e.stopPropagation()
            onConfirm()
            setConfirming(false)
          }}
          className="px-2 py-1 rounded-md text-[10px] font-semibold bg-red-500/20 text-red-400 border border-red-500/30 hover:bg-red-500/30 transition-all"
        >
          {confirmLabel ?? 'Confirm'}
        </button>
        <button
          onClick={(e) => {
            e.stopPropagation()
            setConfirming(false)
          }}
          className="px-2 py-1 rounded-md text-[10px] font-semibold bg-white/5 text-gray-400 border border-white/10 hover:bg-white/8 transition-all"
        >
          Cancel
        </button>
      </span>
    )
  }

  return (
    <button
      onClick={(e) => {
        e.stopPropagation()
        setConfirming(true)
      }}
      disabled={disabled || isLoading}
      title={title}
      className={cn(
        'p-1.5 rounded-md hover:bg-white/6 text-gray-500 transition-colors disabled:opacity-50',
        hoverColor,
      )}
    >
      {isLoading ? (
        <Loader2 className="w-3 h-3 animate-spin" />
      ) : ConfirmIcon ? (
        <ConfirmIcon className="w-3 h-3" />
      ) : (
        <Icon className="w-3 h-3" />
      )}
    </button>
  )
}

// ─── Vault Stats Banner ────────────────────────────────────────────────────────

function VaultStatsBanner({ groups }: { groups: CertGroupDto[] }) {
  const total = groups.length
  const active = groups.filter((g) => g.status === 'ACTIVE').length
  const expired = groups.filter((g) => g.expiryHealthStatus === 'EXPIRED').length
  const expiringSoon = groups.filter((g) => g.expiryHealthStatus === 'EXPIRING_SOON').length
  const totalMembers = groups.reduce((s, g) => s + g.memberCount, 0)

  if (total === 0) return null

  return (
    <div className="flex items-center gap-3 px-6 py-3 border-b border-white/6 bg-[#0c0e14]">
      <StatPill
        icon={<Layers className="w-3.5 h-3.5 text-violet-400" />}
        label="Groups"
        value={`${active}/${total}`}
        color="text-violet-300"
      />
      <StatPill
        icon={<ShieldCheck className="w-3.5 h-3.5 text-indigo-400" />}
        label="Certificates"
        value={totalMembers}
        color="text-indigo-300"
      />
      {expiringSoon > 0 && (
        <StatPill
          icon={<Clock className="w-3.5 h-3.5 text-amber-400" />}
          label="Expiring soon"
          value={expiringSoon}
          color="text-amber-300"
          highlight="amber"
        />
      )}
      {expired > 0 && (
        <StatPill
          icon={<TrendingDown className="w-3.5 h-3.5 text-red-400" />}
          label="Expired"
          value={expired}
          color="text-red-300"
          highlight="red"
        />
      )}
      {expired === 0 && expiringSoon === 0 && (
        <span className="flex items-center gap-1.5 text-[11px] text-emerald-400/80 ml-auto">
          <CheckCircle2 className="w-3.5 h-3.5" /> All certificates valid
        </span>
      )}
    </div>
  )
}

function StatPill({
  icon,
  label,
  value,
  color,
  highlight,
}: {
  icon: React.ReactNode
  label: string
  value: string | number
  color: string
  highlight?: 'amber' | 'red'
}) {
  return (
    <div
      className={cn(
        'flex items-center gap-1.5 px-2.5 py-1 rounded-lg border text-[11px]',
        highlight === 'amber'
          ? 'bg-amber-500/8 border-amber-500/20'
          : highlight === 'red'
            ? 'bg-red-500/8 border-red-500/20'
            : 'bg-white/3 border-white/8',
      )}
    >
      {icon}
      <span className="text-gray-500">{label}:</span>
      <span className={cn('font-semibold', color)}>{value}</span>
    </div>
  )
}

// ─── Group Card ───────────────────────────────────────────────────────────────

function GroupCard({
  group,
  isSelected,
  onSelect,
  onEdit,
  onArchive,
  onDelete,
  onUpload,
  isArchiving,
  isDeleting,
}: {
  group: CertGroupDto
  isSelected: boolean
  onSelect: () => void
  onEdit: () => void
  onArchive: () => void
  onDelete: () => void
  onUpload: () => void
  isArchiving: boolean
  isDeleting: boolean
}) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onSelect()
        }
      }}
      className={cn(
        'relative rounded-xl border p-4 cursor-pointer transition-all group',
        isSelected
          ? 'border-violet-500/50 bg-violet-500/5 shadow-lg shadow-violet-500/5'
          : 'border-white/6 bg-white/2 hover:border-white/12 hover:bg-white/3',
        group.status === 'ARCHIVED' && 'opacity-60',
      )}
    >
      {/* Top-right actions — visible on hover */}
      <div className="absolute top-2.5 right-2.5 flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
        {group.status === 'ACTIVE' && (
          <button
            onClick={(e) => {
              e.stopPropagation()
              onUpload()
            }}
            title="Upload certificate to this group"
            className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-indigo-400 transition-colors"
          >
            <Upload className="w-3 h-3" />
          </button>
        )}
        <button
          onClick={(e) => {
            e.stopPropagation()
            onEdit()
          }}
          title="Edit group"
          className="p-1.5 rounded-md hover:bg-white/6 text-gray-500 hover:text-white transition-colors"
        >
          <Edit2 className="w-3 h-3" />
        </button>
        {group.status === 'ACTIVE' && (
          <ConfirmButton
            onConfirm={onArchive}
            disabled={isArchiving}
            isLoading={isArchiving}
            title="Archive group"
            icon={Archive}
            hoverColor="hover:text-amber-400"
            confirmLabel="Archive"
          />
        )}
        <ConfirmButton
          onConfirm={onDelete}
          disabled={isDeleting}
          isLoading={isDeleting}
          title="Delete group"
          icon={Trash2}
          hoverColor="hover:text-red-400"
          confirmLabel="Delete"
        />
      </div>

      {/* Icon + title */}
      <div className="flex items-start gap-3 mb-3 pr-16">
        <div
          className={cn(
            'w-9 h-9 rounded-lg border flex items-center justify-center shrink-0 mt-0.5',
            group.expiryHealthStatus === 'EXPIRED'
              ? 'bg-red-500/10 border-red-500/20'
              : group.expiryHealthStatus === 'EXPIRING_SOON'
                ? 'bg-amber-500/10 border-amber-500/20'
                : 'bg-violet-500/10 border-violet-500/20',
          )}
        >
          <Layers
            className={cn(
              'w-4 h-4',
              group.expiryHealthStatus === 'EXPIRED'
                ? 'text-red-400'
                : group.expiryHealthStatus === 'EXPIRING_SOON'
                  ? 'text-amber-400'
                  : 'text-violet-400',
            )}
          />
        </div>
        <div className="min-w-0">
          <p className="font-semibold text-sm text-white truncate">{group.alias}</p>
          {/* Gateway TLS key with copy button */}
          <div className="flex items-center gap-0.5 mt-0.5 -ml-0.5">
            <Server className="w-2.5 h-2.5 text-indigo-400 shrink-0 ml-0.5" />
            <span className="text-[10px] text-indigo-300 font-mono truncate max-w-32.5">{group.logicalId}</span>
            <CopyButton text={group.logicalId} />
          </div>
        </div>
      </div>

      {group.description && <p className="text-xs text-gray-500 mb-3 line-clamp-1">{group.description}</p>}

      {/* Stats row */}
      <div className="flex items-center gap-3 text-xs text-gray-500">
        <div className="flex items-center gap-1">
          <Users className="w-3 h-3" />
          <span>
            {group.memberCount} cert{group.memberCount !== 1 ? 's' : ''}
          </span>
        </div>
        <div className="flex items-center gap-1">
          {EXPIRY_ICON[group.expiryHealthStatus]}
          <span
            className={cn(
              group.expiryHealthStatus === 'EXPIRED'
                ? 'text-red-400'
                : group.expiryHealthStatus === 'EXPIRING_SOON'
                  ? 'text-amber-400'
                  : 'text-emerald-400',
            )}
          >
            {group.expiryHealthStatus === 'VALID'
              ? 'Valid'
              : group.expiryHealthStatus === 'EXPIRING_SOON'
                ? 'Expiring soon'
                : 'Expired'}
          </span>
        </div>
        {group.status === 'ARCHIVED' && (
          <span className="text-[10px] text-gray-500 bg-gray-500/10 border border-gray-500/20 px-1.5 py-0.5 rounded-full ml-auto">
            ARCHIVED
          </span>
        )}
        <span className="ml-auto text-gray-600 text-[10px]">{formatDate(group.createdAt)}</span>
      </div>

      {isSelected && <ChevronRight className="absolute right-3 bottom-3.5 w-3 h-3 text-violet-400" />}
    </div>
  )
}

// ─── Member Row ───────────────────────────────────────────────────────────────

function MemberRow({
  cert,
  onRevoke,
  onDelete,
  onView,
  isRevoking,
  isDeleting,
}: {
  cert: CertificateDto
  onRevoke: () => void
  onDelete: () => void
  onView: () => void
  isRevoking: boolean
  isDeleting: boolean
}) {
  return (
    <div className="flex items-center gap-3 py-2.5 px-3 rounded-lg bg-white/2 border border-white/4 group hover:bg-white/3 transition-colors">
      {/* Status indicator dot */}
      <div
        className={cn(
          'w-7 h-7 rounded-md border flex items-center justify-center shrink-0',
          cert.status === 'ACTIVE'
            ? 'bg-indigo-500/10 border-indigo-500/20'
            : cert.status === 'REVOKED'
              ? 'bg-red-500/10 border-red-500/20'
              : 'bg-gray-500/10 border-gray-500/20',
        )}
      >
        <ShieldCheck
          className={cn(
            'w-3.5 h-3.5',
            cert.status === 'ACTIVE' ? 'text-indigo-400' : cert.status === 'REVOKED' ? 'text-red-400' : 'text-gray-500',
          )}
        />
      </div>

      {/* Main content */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <button
            onClick={onView}
            className="text-sm text-white font-medium hover:text-indigo-300 transition-colors text-left truncate max-w-36"
          >
            {cert.alias}
          </button>
          {cert.memberAlias && (
            <span className="text-[10px] font-medium text-violet-400 bg-violet-500/10 px-1.5 py-0.5 rounded shrink-0">
              {cert.memberAlias}
            </span>
          )}
          <span
            className={cn(
              'text-[10px] font-medium px-1.5 py-0.5 rounded-full border shrink-0',
              STATUS_COLOR[cert.status],
            )}
          >
            {cert.status}
          </span>
        </div>
        <div className="flex items-center gap-3 mt-0.5">
          {cert.subjectDn && (
            <span className="text-[10px] text-gray-600 truncate max-w-45">{cert.subjectDn.replace(/^.*?CN=/, '')}</span>
          )}
          <ExpiryBadge notAfter={cert.notAfter} expiryStatus={cert.expiryStatus} />
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
        {cert.status === 'ACTIVE' && (
          <ConfirmButton
            onConfirm={onRevoke}
            disabled={isRevoking}
            isLoading={isRevoking}
            title="Revoke certificate"
            icon={RotateCcw}
            hoverColor="hover:text-amber-400"
            confirmLabel="Revoke"
          />
        )}
        <ConfirmButton
          onConfirm={onDelete}
          disabled={isDeleting}
          isLoading={isDeleting}
          title="Delete certificate"
          icon={Trash2}
          hoverColor="hover:text-red-400"
          confirmLabel="Delete"
        />
      </div>
    </div>
  )
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function CertGroupsPage() {
  const qc = useQueryClient()
  const { user } = useAuthStore()
  const tenantId = user?.tenantId ?? ''

  const [selectedGroup, setSelectedGroup] = useState<CertGroupDto | null>(null)
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [editGroup, setEditGroup] = useState<CertGroupDto | null>(null)
  const [uploadIntoGroup, setUploadIntoGroup] = useState<CertGroupDto | null>(null)
  const [detailCert, setDetailCert] = useState<CertificateDto | null>(null)
  const [statusFilter, setStatusFilter] = useState<string>('')

  const invalidate = useCallback(() => {
    qc.invalidateQueries({ queryKey: ['cert-groups'] })
    qc.invalidateQueries({ queryKey: ['cert-group-detail'] })
    qc.invalidateQueries({ queryKey: ['cert-stats'] })
  }, [qc])

  const { data: pageData, isLoading } = useRealtimeQuery({
    queryKey: ['cert-groups', tenantId, statusFilter],
    queryFn: () => certVaultApi.listGroups({ tenantId, status: statusFilter || undefined, size: 100 }),
    enabled: !!tenantId,
    wsEvents: ['certificate'],
  })

  const { data: groupDetail, isLoading: isLoadingDetail } = useRealtimeQuery({
    queryKey: ['cert-group-detail', selectedGroup?.id, tenantId],
    queryFn: () => certVaultApi.getGroup(selectedGroup!.id, tenantId),
    enabled: !!selectedGroup && !!tenantId,
    wsEvents: ['certificate'],
  })

  const archiveMutation = useMutation({
    mutationFn: (id: string) => certVaultApi.archiveGroup(id, tenantId),
    onSuccess: () => {
      invalidate()
      setSelectedGroup(null)
    },
  })

  const deleteGroupMutation = useMutation({
    mutationFn: (id: string) => certVaultApi.deleteGroup(id, tenantId),
    onSuccess: () => {
      invalidate()
      setSelectedGroup(null)
    },
  })

  const revokeMutation = useMutation({
    mutationFn: (certId: string) => certVaultApi.revokeCertificate(certId, tenantId),
    onSuccess: () => invalidate(),
  })

  const deleteCertMutation = useMutation({
    mutationFn: (certId: string) => certVaultApi.deleteCertificate(certId, tenantId),
    onSuccess: () => invalidate(),
  })

  const groups = pageData?.content ?? []
  const detail = groupDetail as CertGroupDto | undefined

  // Per-status counts for the filter bar badges
  const activeCount = groups.filter((g) => g.status === 'ACTIVE').length
  const archivedCount = groups.filter((g) => g.status === 'ARCHIVED').length

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
            Certificates are organised in groups — the group's{' '}
            <span className="font-mono text-gray-400">logicalId</span> is the stable gateway TLS key
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

      {/* ── Vault stats banner ── */}
      {!isLoading && groups.length > 0 && <VaultStatsBanner groups={groups} />}

      {/* ── Filter bar ── */}
      <div className="flex items-center gap-2 px-6 py-2.5 border-b border-white/6 bg-[#0c0e14]">
        <span className="text-xs text-gray-500 mr-1">Filter:</span>
        {(
          [
            { key: '', label: 'All', count: groups.length },
            { key: 'ACTIVE', label: 'Active', count: activeCount },
            { key: 'ARCHIVED', label: 'Archived', count: archivedCount },
          ] as const
        ).map(({ key, label, count }) => (
          <button
            key={key}
            onClick={() => setStatusFilter(key)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium border transition-all',
              statusFilter === key
                ? 'bg-violet-500/20 text-violet-300 border-violet-500/40'
                : 'bg-transparent text-gray-500 border-white/8 hover:text-gray-300 hover:border-white/16',
            )}
          >
            {label}
            {count > 0 && (
              <span
                className={cn(
                  'inline-flex items-center justify-center w-4 h-4 rounded-full text-[9px] font-bold',
                  statusFilter === key ? 'bg-violet-500/30 text-violet-200' : 'bg-white/8 text-gray-500',
                )}
              >
                {count}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* ── Split panel ── */}
      <div className="flex flex-1 min-h-0 overflow-hidden">
        {/* Left: group list */}
        <div className="w-80 shrink-0 border-r border-white/6 overflow-y-auto p-3.5 space-y-2">
          {isLoading ? (
            <div className="flex items-center justify-center py-12">
              <div className="w-6 h-6 border-2 border-violet-500/30 border-t-violet-500 rounded-full animate-spin" />
            </div>
          ) : groups.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 gap-4 text-center px-4">
              <div className="w-16 h-16 rounded-2xl bg-white/3 border border-white/6 flex items-center justify-center">
                <Layers className="w-7 h-7 text-gray-600" />
              </div>
              <div>
                <p className="text-sm font-semibold text-gray-200 mb-1">No certificate groups yet</p>
                <p className="text-xs text-gray-500 leading-relaxed">
                  Groups bundle certificates under a stable <span className="font-mono text-gray-400">logicalId</span>{' '}
                  key. The gateway always loads certificates via this key — enabling zero-downtime rotation.
                </p>
              </div>
              <div className="flex items-start gap-2 p-3 rounded-lg bg-indigo-500/5 border border-indigo-500/15 text-xs text-left">
                <Info className="w-3.5 h-3.5 text-indigo-400 shrink-0 mt-0.5" />
                <span className="text-indigo-300/80">
                  Workflow: <span className="font-semibold text-indigo-300">Create group</span> → upload certs into it →
                  gateway serves them under the group's logical ID.
                </span>
              </div>
              <button
                onClick={() => setShowCreateForm(true)}
                className="flex items-center gap-2 px-3.5 py-2 bg-violet-600/20 hover:bg-violet-600/30 border border-violet-500/30 text-violet-400 text-xs font-semibold rounded-lg transition-all"
              >
                <Plus className="w-3.5 h-3.5" />
                Create first group
              </button>
            </div>
          ) : (
            groups.map((group) => (
              <GroupCard
                key={group.id}
                group={group}
                isSelected={selectedGroup?.id === group.id}
                onSelect={() => setSelectedGroup(group)}
                onEdit={() => setEditGroup(group)}
                onUpload={() => setUploadIntoGroup(group)}
                onArchive={() => archiveMutation.mutate(group.id)}
                onDelete={() => deleteGroupMutation.mutate(group.id)}
                isArchiving={archiveMutation.isPending && archiveMutation.variables === group.id}
                isDeleting={deleteGroupMutation.isPending && deleteGroupMutation.variables === group.id}
              />
            ))
          )}
        </div>

        {/* Right: group detail */}
        <div className="flex-1 overflow-y-auto">
          {!selectedGroup ? (
            <div className="flex flex-col items-center justify-center h-full gap-4 text-center px-8">
              <div className="w-14 h-14 rounded-2xl bg-white/3 border border-white/6 flex items-center justify-center">
                <Layers className="w-6 h-6 text-gray-600" />
              </div>
              <div>
                <p className="text-sm text-gray-300 font-semibold mb-1">Select a group</p>
                <p className="text-xs text-gray-600 max-w-xs">
                  Choose a certificate group to view its members, upload new certificates, or manage rotation
                </p>
              </div>
            </div>
          ) : isLoadingDetail ? (
            <div className="flex items-center justify-center py-20">
              <div className="w-6 h-6 border-2 border-violet-500/30 border-t-violet-500 rounded-full animate-spin" />
            </div>
          ) : detail ? (
            <div className="p-6 animate-fade-in">
              {/* Group header card */}
              <div className="rounded-xl border border-white/6 bg-white/2 p-4 mb-5">
                <div className="flex items-start justify-between gap-4">
                  <div className="flex items-start gap-3 min-w-0">
                    <div
                      className={cn(
                        'w-10 h-10 rounded-xl border flex items-center justify-center shrink-0',
                        detail.expiryHealthStatus === 'EXPIRED'
                          ? 'bg-red-500/10 border-red-500/20'
                          : detail.expiryHealthStatus === 'EXPIRING_SOON'
                            ? 'bg-amber-500/10 border-amber-500/20'
                            : 'bg-violet-500/10 border-violet-500/20',
                      )}
                    >
                      <Layers
                        className={cn(
                          'w-5 h-5',
                          detail.expiryHealthStatus === 'EXPIRED'
                            ? 'text-red-400'
                            : detail.expiryHealthStatus === 'EXPIRING_SOON'
                              ? 'text-amber-400'
                              : 'text-violet-400',
                        )}
                      />
                    </div>
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <h2 className="text-base font-bold text-white">{detail.alias}</h2>
                        {detail.status === 'ARCHIVED' && (
                          <span className="text-[10px] font-medium text-gray-500 bg-gray-500/10 border border-gray-500/20 px-1.5 py-0.5 rounded-full">
                            ARCHIVED
                          </span>
                        )}
                      </div>
                      {/* Gateway TLS key — prominent with copy */}
                      <div className="flex items-center gap-1.5 mt-1.5 flex-wrap">
                        <Server className="w-3.5 h-3.5 text-indigo-400 shrink-0" />
                        <span className="text-xs text-gray-500">Gateway TLS key:</span>
                        <code className="text-sm font-mono text-indigo-300 bg-indigo-500/10 px-2 py-0.5 rounded border border-indigo-500/20">
                          {detail.logicalId}
                        </code>
                        <CopyButton text={detail.logicalId} />
                      </div>
                      {detail.description && <p className="text-xs text-gray-500 mt-1.5">{detail.description}</p>}
                    </div>
                  </div>

                  {/* Action buttons */}
                  {detail.status === 'ACTIVE' && (
                    <div className="flex items-center gap-2 shrink-0">
                      <button
                        onClick={() => setUploadIntoGroup(detail)}
                        className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-xs font-semibold rounded-lg transition-all"
                      >
                        <Upload className="w-3.5 h-3.5" />
                        Upload Certificate
                      </button>
                    </div>
                  )}
                </div>
              </div>

              {/* Expiry warning banner */}
              {detail.expiryHealthStatus !== 'VALID' && (
                <div
                  className={cn(
                    'flex items-start gap-2 p-3 rounded-lg border mb-5 text-xs',
                    detail.expiryHealthStatus === 'EXPIRED'
                      ? 'bg-red-500/8 border-red-500/20 text-red-300'
                      : 'bg-amber-500/8 border-amber-500/20 text-amber-300',
                  )}
                >
                  {detail.expiryHealthStatus === 'EXPIRED' ? (
                    <ShieldAlert className="w-4 h-4 shrink-0 mt-0.5 text-red-400" />
                  ) : (
                    <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5 text-amber-400" />
                  )}
                  <span>
                    {detail.expiryHealthStatus === 'EXPIRED'
                      ? 'One or more certificates in this group have expired. Upload a new certificate and remove the expired ones to restore valid gateway TLS.'
                      : 'One or more certificates are expiring soon. Upload a replacement to ensure uninterrupted gateway TLS coverage.'}
                  </span>
                </div>
              )}

              {/* Members section header */}
              <div className="flex items-center gap-2 mb-3">
                <Users className="w-4 h-4 text-gray-500" />
                <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Certificates ({detail.members?.length ?? 0})
                </h3>
                <div className="ml-auto flex items-center gap-1">
                  {EXPIRY_ICON[detail.expiryHealthStatus]}
                  <span
                    className={cn(
                      'text-xs',
                      detail.expiryHealthStatus === 'EXPIRED'
                        ? 'text-red-400'
                        : detail.expiryHealthStatus === 'EXPIRING_SOON'
                          ? 'text-amber-400'
                          : 'text-emerald-400',
                    )}
                  >
                    {detail.expiryHealthStatus === 'VALID'
                      ? 'All valid'
                      : detail.expiryHealthStatus === 'EXPIRING_SOON'
                        ? 'Expiring soon'
                        : 'Has expired'}
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
                      Upload a certificate to get started. It will be served by the gateway under{' '}
                      <code className="font-mono text-indigo-300">{detail.logicalId}</code>
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
                  {detail.members.map((cert) => (
                    <MemberRow
                      key={cert.id}
                      cert={cert}
                      onView={() => setDetailCert(cert)}
                      onRevoke={() => revokeMutation.mutate(cert.id)}
                      onDelete={() => deleteCertMutation.mutate(cert.id)}
                      isRevoking={revokeMutation.isPending && revokeMutation.variables === cert.id}
                      isDeleting={deleteCertMutation.isPending && deleteCertMutation.variables === cert.id}
                    />
                  ))}
                </div>
              )}

              {/* Zero-downtime rotation hint */}
              {detail.members && detail.members.length > 0 && detail.status === 'ACTIVE' && (
                <div className="mt-5 p-3 rounded-lg bg-violet-500/5 border border-violet-500/10">
                  <p className="text-xs text-violet-300/70 leading-relaxed">
                    <span className="font-semibold text-violet-300">Zero-downtime rotation:</span> Upload a new
                    certificate → the gateway immediately serves it under{' '}
                    <code className="text-violet-300">{detail.logicalId}</code> → then revoke or remove the old one. No
                    gateway restart or config change required.
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
          onSuccess={() => {
            setShowCreateForm(false)
            invalidate()
          }}
        />
      )}
      {editGroup && (
        <CertGroupFormModal
          tenantId={tenantId}
          group={editGroup}
          onClose={() => setEditGroup(null)}
          onSuccess={() => {
            setEditGroup(null)
            invalidate()
          }}
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
      {detailCert && <CertDetailModal cert={detailCert} onClose={() => setDetailCert(null)} />}
    </div>
  )
}
