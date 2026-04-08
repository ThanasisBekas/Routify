import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { useAuthStore } from '../../store/authStore'
import { exportImportApi } from '../../api/exportImportApi'
import { cn } from '../../lib/utils'
import type { UserRole } from '../../types'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import {
  User,
  Shield,
  Hash,
  Mail,
  Building2,
  Cpu,
  Layers,
  Zap,
  KeyRound,
  Download,
  Upload,
  Moon,
  Monitor,
  ShieldCheck,
  ShieldAlert,
  ExternalLink,
  Loader2,
  ChevronRight,
} from 'lucide-react'

// ─── Constants ────────────────────────────────────────────────────────────────

const ROLE_CONFIG: Record<UserRole, { label: string; color: string }> = {
  SUPER_ADMIN: { label: 'Super Admin', color: 'text-red-400 bg-red-400/10 border-red-400/20' },
  TENANT_ADMIN: { label: 'Tenant Admin', color: 'text-indigo-400 bg-indigo-400/10 border-indigo-400/20' },
  OPERATOR: { label: 'Operator', color: 'text-blue-400 bg-blue-400/10 border-blue-400/20' },
  VIEWER: { label: 'Viewer', color: 'text-gray-400 bg-gray-400/10 border-gray-400/20' },
}

const STACK_ITEMS = [
  { icon: Zap, label: 'Spring Cloud Gateway', desc: 'Zero-downtime dynamic routing via Kafka hot-reload' },
  { icon: Layers, label: 'Route Service', desc: 'Route + filter lifecycle, Outbox pattern' },
  { icon: Shield, label: 'Identity Service', desc: 'JWT RS256, tenants, multi-tenancy' },
  { icon: Cpu, label: 'Java 25', desc: 'Virtual threads, ZGC, sealed classes' },
]

type ThemeOption = 'dark' | 'system'

// ─── Settings Page ────────────────────────────────────────────────────────────

export default function SettingsPage() {
  useDocumentTitle('Settings')
  const navigate = useNavigate()
  const { user } = useAuthStore()
  const roleConfig = user?.role ? ROLE_CONFIG[user.role as UserRole] : null
  const [theme, setTheme] = useState<ThemeOption>('dark')

  // RBAC granular permissions — if the user's JWT token includes a non-empty
  // permissions array, the backend has routify.rbac.granular-enabled=true.
  const granularRbacEnabled = !!(user?.permissions && user.permissions.length > 0)

  // ─── Export mutation ────────────────────────────────────────────────────
  const exportMutation = useMutation({
    mutationFn: (format: 'yaml' | 'json') => exportImportApi.exportConfig({ format }),
    onSuccess: ({ blob, filename }) => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      toast.success('Configuration exported')
    },
    onError: () => toast.error('Failed to export configuration'),
  })

  // ─── Import: navigate to routes page which hosts the import flow ──────
  const handleImportNav = () => navigate('/routes')

  return (
    <div className="flex flex-col h-full animate-fade-in">
      {/* Header */}
      <div className="px-6 py-5 border-b border-white/[0.06] bg-[#0c0e14]">
        <h1 className="text-lg font-bold text-white tracking-tight mb-1">Settings</h1>
        <p className="text-sm text-gray-500">Account details, security, and platform configuration</p>
      </div>

      <div className="flex-1 overflow-auto">
        <div className="max-w-2xl px-6 py-6 space-y-5">
          {/* ───────────── Profile Card ───────────── */}
          <div className="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
            <div className="h-1 bg-gradient-to-r from-indigo-600 via-purple-600 to-indigo-600 opacity-70" />
            <div className="p-6">
              <div className="flex items-center gap-4">
                <div className="relative w-14 h-14 shrink-0">
                  <div className="absolute inset-0 rounded-2xl bg-gradient-to-br from-indigo-500/30 to-purple-600/30 border border-indigo-500/20" />
                  <div className="absolute inset-0 rounded-2xl flex items-center justify-center">
                    <span className="text-2xl font-bold text-indigo-300">
                      {user?.username?.[0]?.toUpperCase() ?? '?'}
                    </span>
                  </div>
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-3 flex-wrap mb-1">
                    <h2 className="text-base font-bold text-white">{user?.username ?? '—'}</h2>
                    {roleConfig && (
                      <span
                        className={cn('text-[11px] px-2 py-0.5 rounded-full font-semibold border', roleConfig.color)}
                      >
                        {roleConfig.label}
                      </span>
                    )}
                  </div>
                  <p className="text-sm text-gray-500 truncate">{user?.email ?? '—'}</p>
                </div>
              </div>
            </div>
          </div>

          {/* ───────────── Account Details ───────────── */}
          <div className="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
            <div className="px-5 py-3.5 border-b border-white/[0.06]">
              <h3 className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">Account Details</h3>
            </div>
            <div>
              {[
                { icon: User, label: 'Username', value: user?.username },
                { icon: Mail, label: 'Email', value: user?.email },
                { icon: Shield, label: 'Role', value: user?.role?.replace('_', ' ') },
                { icon: Building2, label: 'Tenant ID', value: user?.tenantId, mono: true },
                { icon: Hash, label: 'User ID', value: user?.id, mono: true },
              ].map((row, i, arr) => (
                <div
                  key={row.label}
                  className={cn(
                    'flex items-center justify-between px-5 py-3.5',
                    i < arr.length - 1 && 'border-b border-white/[0.04]',
                  )}
                >
                  <div className="flex items-center gap-2.5 text-gray-500 text-sm">
                    <row.icon className="w-3.5 h-3.5 shrink-0" />
                    <span className="text-xs font-medium text-gray-500">{row.label}</span>
                  </div>
                  <span
                    className={cn(
                      'text-sm',
                      row.mono
                        ? 'font-mono text-[11px] text-gray-500 bg-white/[0.04] border border-white/[0.06] px-2 py-0.5 rounded-md'
                        : 'text-white font-medium',
                    )}
                  >
                    {row.value ?? '—'}
                  </span>
                </div>
              ))}
            </div>
          </div>

          {/* ───────────── Security ───────────── */}
          <div className="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
            <div className="px-5 py-3.5 border-b border-white/[0.06]">
              <h3 className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">Security</h3>
            </div>
            <div>
              {/* Password change */}
              <button
                onClick={() => navigate('/change-password')}
                className="w-full flex items-center justify-between px-5 py-3.5 border-b border-white/[0.04] hover:bg-white/[0.02] transition-colors group"
              >
                <div className="flex items-center gap-2.5">
                  <KeyRound className="w-3.5 h-3.5 text-gray-500 shrink-0" />
                  <span className="text-xs font-medium text-gray-500">Password</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-sm text-gray-400 group-hover:text-white transition-colors">
                    Change password
                  </span>
                  <ChevronRight className="w-3.5 h-3.5 text-gray-600 group-hover:text-gray-400 transition-colors" />
                </div>
              </button>

              {/* RBAC granular permissions flag */}
              <div className="flex items-center justify-between px-5 py-3.5">
                <div className="flex items-center gap-2.5">
                  {granularRbacEnabled ? (
                    <ShieldCheck className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
                  ) : (
                    <ShieldAlert className="w-3.5 h-3.5 text-gray-500 shrink-0" />
                  )}
                  <span className="text-xs font-medium text-gray-500">Granular RBAC</span>
                </div>
                <span
                  className={cn(
                    'text-[11px] px-2 py-0.5 rounded-full font-semibold border',
                    granularRbacEnabled
                      ? 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20'
                      : 'text-gray-500 bg-gray-500/10 border-gray-500/20',
                  )}
                >
                  {granularRbacEnabled ? 'Enabled' : 'Disabled'}
                </span>
              </div>
            </div>
          </div>

          {/* ───────────── Export / Import Shortcuts ───────────── */}
          <div className="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
            <div className="px-5 py-3.5 border-b border-white/[0.06]">
              <h3 className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">Export / Import</h3>
            </div>
            <div className="p-5 space-y-3">
              <p className="text-xs text-gray-500 leading-relaxed">
                Export the full gateway configuration (routes, filters, and settings) as YAML or JSON.
                Import a configuration file to apply changes via Kafka commands.
              </p>
              <div className="flex flex-wrap gap-2">
                <button
                  onClick={() => exportMutation.mutate('yaml')}
                  disabled={exportMutation.isPending}
                  className="flex items-center gap-2 px-3.5 py-2 text-xs font-semibold text-indigo-300 bg-indigo-500/10 border border-indigo-500/20 rounded-xl hover:bg-indigo-500/15 transition-colors disabled:opacity-50"
                >
                  {exportMutation.isPending ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <Download className="w-3.5 h-3.5" />
                  )}
                  Export YAML
                </button>
                <button
                  onClick={() => exportMutation.mutate('json')}
                  disabled={exportMutation.isPending}
                  className="flex items-center gap-2 px-3.5 py-2 text-xs font-semibold text-indigo-300 bg-indigo-500/10 border border-indigo-500/20 rounded-xl hover:bg-indigo-500/15 transition-colors disabled:opacity-50"
                >
                  {exportMutation.isPending ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <Download className="w-3.5 h-3.5" />
                  )}
                  Export JSON
                </button>
                <button
                  onClick={handleImportNav}
                  className="flex items-center gap-2 px-3.5 py-2 text-xs font-semibold text-gray-400 bg-white/[0.04] border border-white/[0.07] rounded-xl hover:bg-white/[0.07] transition-colors"
                >
                  <Upload className="w-3.5 h-3.5" />
                  Import Config
                  <ExternalLink className="w-3 h-3 text-gray-600" />
                </button>
              </div>
            </div>
          </div>

          {/* ───────────── Appearance ───────────── */}
          <div className="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
            <div className="px-5 py-3.5 border-b border-white/[0.06]">
              <h3 className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">Appearance</h3>
            </div>
            <div className="p-5">
              <div className="flex gap-2">
                {(
                  [
                    { value: 'dark', icon: Moon, label: 'Dark' },
                    { value: 'system', icon: Monitor, label: 'System' },
                  ] as { value: ThemeOption; icon: React.ElementType; label: string }[]
                ).map((opt) => (
                  <button
                    key={opt.value}
                    onClick={() => setTheme(opt.value)}
                    className={cn(
                      'flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs font-semibold border transition-all',
                      theme === opt.value
                        ? 'bg-indigo-500/15 border-indigo-500/40 text-indigo-300'
                        : 'bg-white/[0.03] border-white/[0.07] text-gray-400 hover:bg-white/[0.06]',
                    )}
                  >
                    <opt.icon className="w-3.5 h-3.5" />
                    {opt.label}
                  </button>
                ))}
              </div>
              {theme === 'system' && (
                <p className="text-[11px] text-gray-600 mt-2.5 pl-1">
                  Follows your operating system preference. Currently resolves to dark mode.
                </p>
              )}
            </div>
          </div>

          {/* ───────────── Platform Info ───────────── */}
          <div className="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
            <div className="px-5 py-3.5 border-b border-white/[0.06]">
              <h3 className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">Platform</h3>
            </div>
            <div className="grid grid-cols-2">
              {[
                { label: 'Name', value: 'Routify' },
                { label: 'Version', value: '2.0.2-SNAPSHOT' },
                { label: 'Environment', value: import.meta.env.MODE === 'production' ? 'Production' : 'Development' },
                { label: 'API Base', value: import.meta.env.VITE_API_BASE_URL ?? 'localhost:8082' },
                { label: 'Spring Boot', value: '4.0.5' },
                { label: 'Spring Cloud', value: '2025.1.1' },
              ].map((item, i, arr) => (
                <div
                  key={item.label}
                  className={cn(
                    'px-5 py-4',
                    i % 2 === 0 && i < arr.length - 1 && 'border-r border-white/[0.04]',
                    i < arr.length - 2 && 'border-b border-white/[0.04]',
                  )}
                >
                  <p className="text-[10px] font-bold text-gray-600 uppercase tracking-widest mb-1.5">{item.label}</p>
                  <p className="text-sm text-gray-300 font-mono truncate">{item.value}</p>
                </div>
              ))}
            </div>
          </div>

          {/* ───────────── Architecture ───────────── */}
          <div className="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
            <div className="px-5 py-3.5 border-b border-white/[0.06]">
              <h3 className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">Architecture</h3>
            </div>
            <div className="p-5 grid grid-cols-2 gap-3">
              {STACK_ITEMS.map((item) => (
                <div
                  key={item.label}
                  className="flex items-start gap-3 p-3 rounded-xl bg-white/[0.02] border border-white/[0.05]"
                >
                  <div className="w-7 h-7 rounded-lg bg-indigo-500/15 border border-indigo-500/20 flex items-center justify-center shrink-0 mt-0.5">
                    <item.icon className="w-3.5 h-3.5 text-indigo-400" />
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-gray-300">{item.label}</p>
                    <p className="text-[11px] text-gray-600 mt-0.5 leading-relaxed">{item.desc}</p>
                  </div>
                </div>
              ))}
            </div>
            <div className="px-5 pb-5">
              <div className="grid grid-cols-2 gap-3">
                {[
                  { label: 'Admin API', desc: 'BFF + WebSocket/STOMP events' },
                  { label: 'Audit Service', desc: 'Immutable Kafka event log' },
                  { label: 'Redis', desc: 'Route cache & rate limiting' },
                  { label: 'Kafka 3.9', desc: 'KRaft mode event backbone' },
                ].map((item) => (
                  <div
                    key={item.label}
                    className="flex items-start gap-3 p-3 rounded-xl bg-white/[0.02] border border-white/[0.05]"
                  >
                    <div className="w-1.5 h-1.5 rounded-full bg-gray-600 mt-1.5 shrink-0" />
                    <div>
                      <p className="text-xs font-semibold text-gray-400">{item.label}</p>
                      <p className="text-[11px] text-gray-600 mt-0.5">{item.desc}</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
