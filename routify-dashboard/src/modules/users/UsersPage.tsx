import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { usersApi } from '../../api/usersApi'
import { authApi } from '../../api/authApi'
import { tenantsApi } from '../../api/tenantsApi'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import { useAuthStore } from '../../store/authStore'
import { cn, extractApiError } from '../../lib/utils'
import type { TenantDto, UserDto, UserRole, CreateUserRequest } from '../../types'
import {
  Users, Plus, Pencil, Trash2, KeyRound,
  Loader2, X, Eye, EyeOff, AlertCircle, ShieldCheck, CheckCircle2,
  Building2, ChevronDown, Shield, Eye as EyeIcon, Wrench,
} from 'lucide-react'

// ─── Constants ────────────────────────────────────────────────────────────────

const ROLE_CONFIG: Record<UserRole, { label: string; desc: string; color: string; icon: React.ReactNode }> = {
  SUPER_ADMIN:  { label: 'Super Admin',  desc: 'Platform-wide access', icon: <Shield className="w-3.5 h-3.5" />,  color: 'text-red-400 bg-red-400/10 border-red-400/20' },
  TENANT_ADMIN: { label: 'Tenant Admin', desc: 'Full workspace access', icon: <ShieldCheck className="w-3.5 h-3.5" />, color: 'text-indigo-400 bg-indigo-400/10 border-indigo-400/20' },
  OPERATOR:     { label: 'Operator',     desc: 'Manage routes & filters', icon: <Wrench className="w-3.5 h-3.5" />,    color: 'text-blue-400 bg-blue-400/10 border-blue-400/20' },
  VIEWER:       { label: 'Viewer',       desc: 'Read-only access',      icon: <EyeIcon className="w-3.5 h-3.5" />,  color: 'text-gray-400 bg-gray-400/10 border-gray-400/20' },
}

// Roles a TENANT_ADMIN / SUPER_ADMIN can assign (SUPER_ADMIN is not assignable via this form)
const ASSIGNABLE_ROLES: UserRole[] = ['TENANT_ADMIN', 'OPERATOR', 'VIEWER']

// ─── Shared helpers ───────────────────────────────────────────────────────────

function FormField({ label, hint, error, children }: {
  label: string; hint?: string; error?: string; children: React.ReactNode
}) {
  return (
    <div className="space-y-1.5">
      <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">{label}</label>
      {children}
      {hint && !error && <p className="text-xs text-gray-600 pl-0.5">{hint}</p>}
      {error && <p className="text-xs text-red-400 pl-0.5">{error}</p>}
    </div>
  )
}

// ─── Custom Workspace picker ──────────────────────────────────────────────────

function WorkspacePicker({
  tenants,
  value,
  onChange,
  currentTenantId,
}: {
  tenants: TenantDto[]
  value: string
  onChange: (id: string) => void
  currentTenantId?: string
}) {
  const [open, setOpen] = useState(false)
  const selected = tenants.find(t => t.id === value)

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center gap-2.5 bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white hover:border-indigo-500/50 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/20 transition-all"
      >
        <Building2 className="w-4 h-4 text-gray-500 shrink-0" />
        <span className="flex-1 text-left truncate">
          {selected
            ? <>
                <span className="text-white">{selected.name}</span>
                {selected.id === currentTenantId && (
                  <span className="ml-1.5 text-[10px] text-indigo-400 bg-indigo-400/10 border border-indigo-400/20 px-1.5 py-0.5 rounded-full font-semibold">current</span>
                )}
              </>
            : <span className="text-gray-600">Select workspace…</span>
          }
        </span>
        <ChevronDown className={cn('w-3.5 h-3.5 text-gray-500 transition-transform shrink-0', open && 'rotate-180')} />
      </button>

      {open && (
        <div className="absolute z-20 mt-1.5 w-full bg-[#0e1117] border border-white/[0.09] rounded-xl shadow-2xl overflow-hidden max-h-52 overflow-y-auto">
          {tenants.map(t => (
            <button
              key={t.id}
              type="button"
              onClick={() => { onChange(t.id); setOpen(false) }}
              className={cn(
                'w-full flex items-center gap-2.5 px-3 py-2.5 text-sm text-left transition-colors',
                t.id === value
                  ? 'bg-indigo-500/15 text-indigo-300'
                  : 'text-gray-300 hover:bg-white/[0.05]',
              )}
            >
              <Building2 className="w-3.5 h-3.5 shrink-0 text-gray-500" />
              <span className="flex-1 truncate font-medium">{t.name}</span>
              <span className="text-[10px] font-mono text-gray-600 shrink-0">{t.slug}</span>
              {t.id === currentTenantId && (
                <span className="text-[10px] text-indigo-400 bg-indigo-400/10 border border-indigo-400/20 px-1.5 py-0.5 rounded-full font-semibold shrink-0">current</span>
              )}
              {t.status !== 'ACTIVE' && (
                <span className="text-[10px] text-yellow-400 bg-yellow-400/10 border border-yellow-400/20 px-1.5 py-0.5 rounded-full font-semibold shrink-0">{t.status}</span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ─── Custom Role picker ───────────────────────────────────────────────────────

function RolePicker({ value, onChange }: { value: UserRole; onChange: (r: UserRole) => void }) {
  return (
    <div className="grid grid-cols-3 gap-2">
      {ASSIGNABLE_ROLES.map(r => {
        const cfg = ROLE_CONFIG[r]
        const active = value === r
        return (
          <button
            key={r}
            type="button"
            onClick={() => onChange(r)}
            className={cn(
              'flex flex-col items-center gap-1.5 py-3 px-2 rounded-xl border text-center transition-all',
              active
                ? 'bg-indigo-500/15 border-indigo-500/40 text-indigo-300'
                : 'bg-white/[0.03] border-white/[0.07] text-gray-400 hover:bg-white/[0.06] hover:text-gray-200',
            )}
          >
            <span className={cn('transition-colors', active ? 'text-indigo-400' : 'text-gray-500')}>
              {cfg.icon}
            </span>
            <span className="text-[11px] font-semibold leading-tight">{cfg.label}</span>
            <span className="text-[10px] text-gray-600 leading-tight">{cfg.desc}</span>
          </button>
        )
      })}
    </div>
  )
}

// ─── Reset password modal ─────────────────────────────────────────────────────

function ResetPasswordModal({ target, onClose }: { target: UserDto; onClose: () => void }) {
  const [password, setPassword] = useState('')
  const [showPass, setShowPass] = useState(false)
  const [success,  setSuccess]  = useState(false)
  const [error,    setError]    = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => usersApi.resetPassword(target.id, password),
    onSuccess: () => setSuccess(true),
    onError: (e: unknown) =>
      setError(extractApiError(e, 'Failed to reset password')),
  })

  const inputCls = "w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all"

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-sm shadow-2xl">
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-lg bg-amber-500/20 border border-amber-500/30 flex items-center justify-center">
              <KeyRound className="w-3.5 h-3.5 text-amber-400" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-white">Reset Password</h2>
              <p className="text-xs text-gray-500 mt-0.5">for {target.username}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>
        <div className="p-6 space-y-4">
          <p className="text-xs text-gray-500 leading-relaxed">
            Set a temporary password for <span className="text-gray-300 font-semibold">{target.username}</span>.
            They will be required to change it on next login.
          </p>
          {!success ? (
            <>
              <FormField label="Temporary Password">
                <div className="relative">
                  <input type={showPass ? 'text' : 'password'} required autoFocus
                    value={password} onChange={e => setPassword(e.target.value)}
                    className={cn(inputCls, 'pr-10')} placeholder="Min 8 characters" />
                  <button type="button" onClick={() => setShowPass(p => !p)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300 transition-colors">
                    {showPass ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </FormField>
              {error && (
                <div className="flex items-start gap-2.5 p-3 bg-red-500/[0.08] border border-red-500/20 rounded-xl">
                  <AlertCircle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
                  <span className="text-sm text-red-300">{error}</span>
                </div>
              )}
              <div className="flex justify-end gap-2 pt-1">
                <button type="button" onClick={onClose}
                  className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors">
                  Cancel
                </button>
                <button onClick={() => { setError(null); mutation.mutate() }}
                  disabled={mutation.isPending || password.length < 8}
                  className="px-4 py-2 bg-amber-600 hover:bg-amber-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all">
                  {mutation.isPending
                    ? <span className="flex items-center gap-2"><Loader2 className="w-3.5 h-3.5 animate-spin" /> Resetting…</span>
                    : 'Reset Password'}
                </button>
              </div>
            </>
          ) : (
            <div className="space-y-4">
              <div className="flex items-center gap-2.5 p-3.5 bg-emerald-500/[0.08] border border-emerald-500/20 rounded-xl">
                <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                <p className="text-sm text-emerald-300">
                  Password reset. <span className="font-semibold">{target.username}</span> must change it on next login.
                </p>
              </div>
              <div className="flex justify-end">
                <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors">Close</button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// ─── Self-service change-password modal ───────────────────────────────────────

function ChangeOwnPasswordModal({ userId, onClose }: { userId: string; onClose: () => void }) {
  const { user: currentUser, setUser } = useAuthStore()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword,     setNewPassword]      = useState('')
  const [confirmPassword, setConfirmPassword]  = useState('')
  const [showCurrent,     setShowCurrent]      = useState(false)
  const [showNew,         setShowNew]          = useState(false)
  const [showConfirm,     setShowConfirm]      = useState(false)
  const [success,         setSuccess]          = useState(false)
  const [error,           setError]            = useState<string | null>(null)

  const passwordsMatch  = newPassword === confirmPassword
  const newPasswordLong = newPassword.length >= 8

  const mutation = useMutation({
    mutationFn: () => authApi.changePassword(userId, currentPassword, newPassword),
    onSuccess: () => {
      if (currentUser) setUser({ ...currentUser, mustChangePassword: false })
      setSuccess(true)
    },
    onError: (e: unknown) =>
      setError(extractApiError(e, 'Failed to change password')),
  })

  const inputCls = "w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all pr-10"

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-sm shadow-2xl">
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center">
              <KeyRound className="w-3.5 h-3.5 text-indigo-400" />
            </div>
            <h2 className="text-sm font-bold text-white">Change Your Password</h2>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>
        <div className="p-6 space-y-4">
          {!success ? (
            <>
              <FormField label="Current Password">
                <div className="relative">
                  <input type={showCurrent ? 'text' : 'password'} required autoFocus
                    value={currentPassword} onChange={e => setCurrentPassword(e.target.value)}
                    className={inputCls} placeholder="••••••••" />
                  <button type="button" onClick={() => setShowCurrent(v => !v)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300 transition-colors">
                    {showCurrent ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </FormField>
              <FormField label="New Password" error={newPassword && !newPasswordLong ? 'Min 8 characters' : undefined}>
                <div className="relative">
                  <input type={showNew ? 'text' : 'password'} required
                    value={newPassword} onChange={e => setNewPassword(e.target.value)}
                    className={inputCls} placeholder="Min 8 characters" />
                  <button type="button" onClick={() => setShowNew(v => !v)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300 transition-colors">
                    {showNew ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </FormField>
              <FormField label="Confirm New Password" error={confirmPassword && !passwordsMatch ? 'Passwords do not match' : undefined}>
                <div className="relative">
                  <input type={showConfirm ? 'text' : 'password'} required
                    value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)}
                    className={inputCls} placeholder="••••••••" />
                  <button type="button" onClick={() => setShowConfirm(v => !v)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300 transition-colors">
                    {showConfirm ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </FormField>
              {error && (
                <div className="flex items-start gap-2.5 p-3 bg-red-500/[0.08] border border-red-500/20 rounded-xl">
                  <AlertCircle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
                  <span className="text-sm text-red-300">{error}</span>
                </div>
              )}
              <div className="flex justify-end gap-2 pt-1">
                <button type="button" onClick={onClose}
                  className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors">
                  Cancel
                </button>
                <button onClick={() => { setError(null); mutation.mutate() }}
                  disabled={mutation.isPending || !passwordsMatch || !newPasswordLong}
                  className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all">
                  {mutation.isPending
                    ? <span className="flex items-center gap-2"><Loader2 className="w-3.5 h-3.5 animate-spin" /> Saving…</span>
                    : 'Change Password'}
                </button>
              </div>
            </>
          ) : (
            <div className="space-y-4">
              <div className="flex items-center gap-2.5 p-3.5 bg-emerald-500/[0.08] border border-emerald-500/20 rounded-xl">
                <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                <p className="text-sm text-emerald-300">Password changed successfully.</p>
              </div>
              <div className="flex justify-end">
                <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors">Close</button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// ─── User create / edit modal ─────────────────────────────────────────────────

function UserModal({
  editing,
  onClose,
  onSaved,
  isSuperAdmin,
  tenants,
  tenantsLoading,
}: {
  editing?: UserDto
  onClose: () => void
  onSaved: () => void
  isSuperAdmin: boolean
  tenants: TenantDto[]
  tenantsLoading: boolean
}) {
  const currentUser = useAuthStore(s => s.user)
  const isEdit = !!editing
  const [username,       setUsername]       = useState(editing?.username ?? '')
  const [email,          setEmail]          = useState(editing?.email ?? '')
  const [password,       setPassword]       = useState('')
  const [showPass,       setShowPass]       = useState(false)
  const [role,           setRole]           = useState<UserRole>(editing?.role ?? 'VIEWER')
  const [targetTenantId, setTargetTenantId] = useState<string>(currentUser?.tenantId ?? '')
  const [error,          setError]          = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (req: CreateUserRequest) =>
      usersApi.create(req, isSuperAdmin ? targetTenantId : undefined),
    onSuccess: onSaved,
    onError: (e: unknown) => setError(extractApiError(e, 'Failed to create user')),
  })

  const updateMutation = useMutation({
    mutationFn: () => usersApi.updateRole(editing!.id, role),
    onSuccess: onSaved,
    onError: (e: unknown) => setError(extractApiError(e, 'Failed to update user')),
  })

  const isPending = createMutation.isPending || updateMutation.isPending

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (isEdit) updateMutation.mutate()
    else createMutation.mutate({ username, email, password, role })
  }

  const inputCls = "w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all"

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4 animate-fade-in">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-md shadow-2xl animate-fade-in-up">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center">
              <ShieldCheck className="w-3.5 h-3.5 text-indigo-400" />
            </div>
            <h2 className="text-sm font-bold text-white">{isEdit ? 'Edit User' : 'Create User'}</h2>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-5">
          {!isEdit && (
            <>
              {/* ── Workspace picker (SUPER_ADMIN only) ── */}
              {isSuperAdmin && (
                <FormField label="Workspace" hint="User will be created in this workspace">
                  {tenantsLoading ? (
                    <div className="flex items-center gap-2 text-gray-500 text-sm py-2.5 px-3 bg-white/[0.04] border border-white/[0.08] rounded-lg">
                      <Loader2 className="w-3.5 h-3.5 animate-spin" /> Loading workspaces…
                    </div>
                  ) : (
                    <WorkspacePicker
                      tenants={tenants.filter(t => t.status === 'ACTIVE')}
                      value={targetTenantId}
                      onChange={setTargetTenantId}
                      currentTenantId={currentUser?.tenantId}
                    />
                  )}
                </FormField>
              )}

              {/* ── Identity fields ── */}
              <FormField label="Username">
                <input required value={username} onChange={e => setUsername(e.target.value)}
                  className={inputCls} placeholder="jane.doe" />
              </FormField>
              <FormField label="Email">
                <input required type="email" value={email} onChange={e => setEmail(e.target.value)}
                  className={inputCls} placeholder="jane@example.com" />
              </FormField>
              <FormField label="Password">
                <div className="relative">
                  <input required type={showPass ? 'text' : 'password'} value={password}
                    onChange={e => setPassword(e.target.value)}
                    className={cn(inputCls, 'pr-10')} placeholder="Min 8 characters" />
                  <button type="button" onClick={() => setShowPass(p => !p)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300 transition-colors">
                    {showPass ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </FormField>
            </>
          )}

          {/* ── Role card-picker ── */}
          <FormField label="Role">
            <RolePicker value={role} onChange={setRole} />
          </FormField>

          {error && (
            <div className="flex items-start gap-2.5 p-3.5 bg-red-500/[0.08] border border-red-500/20 rounded-xl">
              <AlertCircle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
              <span className="text-sm text-red-300">{error}</span>
            </div>
          )}

          <div className="flex justify-end gap-2 pt-1">
            <button type="button" onClick={onClose}
              className="px-4 py-2 text-sm text-gray-400 hover:text-white hover:bg-white/[0.04] rounded-lg transition-colors">
              Cancel
            </button>
            <button type="submit"
              disabled={isPending || (isSuperAdmin && !isEdit && !targetTenantId)}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20">
              {isPending
                ? <span className="flex items-center gap-2"><Loader2 className="w-3.5 h-3.5 animate-spin" /> Saving…</span>
                : isEdit ? 'Save Changes' : 'Create User'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ─── Users Page ───────────────────────────────────────────────────────────────

export default function UsersPage() {
  const qc = useQueryClient()
  const { user: currentUser } = useAuthStore()
  const isAdmin      = currentUser?.role === 'TENANT_ADMIN' || currentUser?.role === 'SUPER_ADMIN'
  const isSuperAdmin = currentUser?.role === 'SUPER_ADMIN'

  const [showModal,     setShowModal]     = useState(false)
  const [editingUser,   setEditingUser]   = useState<UserDto | undefined>()
  const [resetTarget,   setResetTarget]   = useState<UserDto | undefined>()
  const [showChangeOwn, setShowChangeOwn] = useState(false)

  const { data, isLoading } = useRealtimeQuery({
    queryKey: ['users'],
    queryFn: () => usersApi.list({ size: 100 }),
    wsEvents: ['user'],
  })

  // Fetch all tenants so we can (a) show workspace name in the table and
  // (b) populate the workspace picker in the create modal — both need the same data.
  const { data: tenantsData, isLoading: tenantsLoading } = useRealtimeQuery({
    queryKey: ['tenants-for-user-create'],
    queryFn: () => tenantsApi.list(0, 200),
    enabled: isSuperAdmin,
    wsEvents: ['tenant'],
  })
  const tenants = tenantsData?.content ?? []
  // Build id → tenant map for O(1) lookup in the table
  const tenantMap = Object.fromEntries(tenants.map(t => [t.id, t]))

  const deleteMutation = useMutation({
    mutationFn: (id: string) => usersApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
  })

  const users: UserDto[] = data?.content ?? []

  const openCreate = () => { setEditingUser(undefined); setShowModal(true) }
  const openEdit   = (u: UserDto) => { setEditingUser(u); setShowModal(true) }
  const onSaved    = () => { setShowModal(false); setEditingUser(undefined); qc.invalidateQueries({ queryKey: ['users'] }) }

  return (
    <div className="flex flex-col h-full animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-5 border-b border-white/[0.06] bg-[#0c0e14]">
        <div>
          <h1 className="text-lg font-bold text-white tracking-tight mb-1">Users</h1>
          <p className="text-sm text-gray-500">
            {users.length} user{users.length !== 1 ? 's' : ''} {isSuperAdmin ? 'across all workspaces' : 'in this workspace'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {currentUser && (
            <button onClick={() => setShowChangeOwn(true)}
              className="flex items-center gap-2 px-3.5 py-2 border border-white/[0.08] hover:border-white/[0.15] text-gray-400 hover:text-white text-sm font-semibold rounded-lg transition-all">
              <KeyRound className="w-4 h-4" />
              Change My Password
            </button>
          )}
          {isAdmin && (
            <button onClick={openCreate}
              className="flex items-center gap-2 px-3.5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20">
              <Plus className="w-4 h-4" />
              New User
            </button>
          )}
        </div>
      </div>

      {/* Table */}
      <div className="flex-1 overflow-auto">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center py-24 gap-3">
            <div className="w-7 h-7 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
            <p className="text-sm text-gray-500">Loading users…</p>
          </div>
        ) : users.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-24 gap-4">
            <div className="w-16 h-16 rounded-2xl bg-white/[0.03] border border-white/[0.06] flex items-center justify-center">
              <Users className="w-7 h-7 text-gray-600" />
            </div>
            <div className="text-center">
              <p className="text-sm font-medium text-gray-300 mb-1">No users yet</p>
              <p className="text-xs text-gray-600">Create the first user to grant access</p>
            </div>
            {isAdmin && (
              <button onClick={openCreate}
                className="flex items-center gap-2 px-4 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-sm font-medium rounded-lg transition-all">
                <Plus className="w-4 h-4" />
                Create first user
              </button>
            )}
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b border-white/[0.06] bg-[#0c0e14]">
                <th className="px-6 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">User</th>
                <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Email</th>
                {/* Workspace column — SUPER_ADMIN only */}
                {isSuperAdmin && (
                  <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Workspace</th>
                )}
                <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Role</th>
                <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Status</th>
                <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Last Login</th>
                <th className="px-4 py-3 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map(u => {
                const workspace = isSuperAdmin ? tenantMap[u.tenantId] : null
                return (
                  <tr key={u.id} className="border-b border-white/[0.04] hover:bg-white/[0.025] group transition-colors">
                    {/* User */}
                    <td className="px-6 py-3.5">
                      <div className="flex items-center gap-3">
                        <div className={cn(
                          'w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold shrink-0 border',
                          u.id === currentUser?.id
                            ? 'bg-indigo-600/30 border-indigo-500/40 text-indigo-200'
                            : 'bg-white/[0.05] border-white/[0.08] text-gray-400',
                        )}>
                          {u.username[0].toUpperCase()}
                        </div>
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="font-semibold text-white text-sm">{u.username}</span>
                          {u.id === currentUser?.id && (
                            <span className="text-[10px] font-semibold text-indigo-400 bg-indigo-400/10 border border-indigo-400/20 px-1.5 py-0.5 rounded-full">you</span>
                          )}
                          {u.mustChangePassword && (
                            <span className="text-[10px] font-semibold text-amber-400 bg-amber-400/10 border border-amber-400/20 px-1.5 py-0.5 rounded-full">must change pw</span>
                          )}
                        </div>
                      </div>
                    </td>
                    {/* Email */}
                    <td className="px-4 py-3.5">
                      <span className="text-xs text-gray-400 font-mono">{u.email}</span>
                    </td>
                    {/* Workspace — SUPER_ADMIN only */}
                    {isSuperAdmin && (
                      <td className="px-4 py-3.5">
                        {workspace ? (
                          <div className="flex items-center gap-1.5">
                            <Building2 className="w-3.5 h-3.5 text-gray-600 shrink-0" />
                            <span className="text-xs text-gray-300 font-medium">{workspace.name}</span>
                            <code className="text-[10px] text-indigo-400 bg-indigo-500/10 px-1.5 py-0.5 rounded">{workspace.slug}</code>
                          </div>
                        ) : (
                          <span className="text-xs text-gray-700">—</span>
                        )}
                      </td>
                    )}
                    {/* Role */}
                    <td className="px-4 py-3.5">
                      <span className={cn('text-[11px] px-2 py-1 rounded-full font-semibold border', ROLE_CONFIG[u.role].color)}>
                        {ROLE_CONFIG[u.role].label}
                      </span>
                    </td>
                    {/* Status */}
                    <td className="px-4 py-3.5">
                      <span className={cn(
                        'inline-flex items-center gap-1.5 text-[11px] px-2 py-1 rounded-full font-semibold border',
                        u.status === 'ACTIVE'
                          ? 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20'
                          : 'text-red-400 bg-red-400/10 border-red-400/20',
                      )}>
                        <span className={cn('w-1.5 h-1.5 rounded-full', u.status === 'ACTIVE' ? 'bg-emerald-400' : 'bg-red-400')} />
                        {u.status}
                      </span>
                    </td>
                    {/* Last Login */}
                    <td className="px-4 py-3.5 text-xs text-gray-500">
                      {u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString() : <span className="text-gray-700">Never</span>}
                    </td>
                    {/* Actions */}
                    <td className="px-4 py-3.5">
                      <div className="flex items-center justify-end gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                        {isAdmin && (
                          <button onClick={() => openEdit(u)} title="Edit role"
                            className="p-1.5 rounded-md text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors">
                            <Pencil className="w-3.5 h-3.5" />
                          </button>
                        )}
                        {isAdmin && u.id !== currentUser?.id && (
                          <button onClick={() => setResetTarget(u)} title="Reset password"
                            className="p-1.5 rounded-md text-amber-400 hover:bg-amber-400/10 transition-colors">
                            <KeyRound className="w-3.5 h-3.5" />
                          </button>
                        )}
                        {isAdmin && u.id !== currentUser?.id && (
                          <button
                            onClick={() => { if (window.confirm(`Delete user "${u.username}"?`)) deleteMutation.mutate(u.id) }}
                            title="Delete user"
                            className="p-1.5 rounded-md text-red-400 hover:bg-red-400/10 transition-colors">
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {showModal && (
        <UserModal
          editing={editingUser}
          onClose={() => setShowModal(false)}
          onSaved={onSaved}
          isSuperAdmin={isSuperAdmin}
          tenants={tenants}
          tenantsLoading={tenantsLoading}
        />
      )}
      {resetTarget && (
        <ResetPasswordModal target={resetTarget} onClose={() => setResetTarget(undefined)} />
      )}
      {showChangeOwn && currentUser && (
        <ChangeOwnPasswordModal userId={currentUser.id} onClose={() => setShowChangeOwn(false)} />
      )}
    </div>
  )
}
