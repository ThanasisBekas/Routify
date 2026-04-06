import { Outlet, NavLink } from 'react-router-dom'
import {
  Route,
  Filter,
  ClipboardList,
  Users,
  Settings,
  LogOut,
  Zap,
  Activity,
  Server,
  ShieldCheck,
  Building2,
  Key,
} from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { useAuthStore } from '../store/authStore'
import { useWsStore } from '../store/wsStore'
import { authApi } from '../api/authApi'
import { tenantsApi } from '../api/tenantsApi'
import { cn } from '../lib/utils'
import { ErrorBoundary } from './ErrorBoundary'

const NAV_ITEMS = [
  { to: '/routes', label: 'Routes', icon: Route, desc: 'Manage gateway routes' },
  { to: '/filters', label: 'Filters', icon: Filter, desc: 'Reusable filter definitions' },
  { to: '/gateway', label: 'Gateway', icon: Server, desc: 'Gateway configuration' },
  { to: '/certificates', label: 'Cert Vault', icon: ShieldCheck, desc: 'Inbound TLS certificates' },
  { to: '/audit', label: 'Audit', icon: ClipboardList, desc: 'Audit log & analytics' },
  { to: '/users', label: 'Users', icon: Users, desc: 'User management' },
  { to: '/api-keys', label: 'API Keys', icon: Key, desc: 'API key management' },
  { to: '/settings', label: 'Settings', icon: Settings, desc: 'Platform settings' },
]

const SUPER_ADMIN_NAV_ITEMS = [
  { to: '/workspaces', label: 'Workspaces', icon: Building2, desc: 'Tenant workspace management' },
]

export default function AppLayout() {
  const { user, logout } = useAuthStore()

  const wsStatus = useWsStore((s) => s.status)
  const recentEvents = useWsStore((s) => s.recentEvents)

  const isConnected = wsStatus === 'CONNECTED'
  const isSuperAdmin = user?.role === 'SUPER_ADMIN'

  // Fetch the current workspace details to show workspace name in sidebar
  const { data: currentTenant } = useQuery({
    queryKey: ['current-tenant', user?.tenantId],
    queryFn: () => tenantsApi.get(user!.tenantId),
    enabled: !!user?.tenantId,
    staleTime: 5 * 60_000,
  })

  const handleLogout = async () => {
    await authApi.logout()
    logout()
  }

  const initials = user?.username?.[0]?.toUpperCase() ?? '?'

  return (
    <div className="flex h-screen bg-[#080a0f] text-white overflow-hidden">
      {/* Sidebar */}
      <aside className="w-[220px] flex flex-col shrink-0 border-r border-white/[0.06] bg-[#0c0e14]">
        {/* Logo */}
        <div className="px-4 pt-5 pb-4">
          <div className="flex items-center gap-3">
            <div className="relative w-8 h-8 shrink-0">
              <div className="absolute inset-0 rounded-lg bg-gradient-to-br from-indigo-500 to-purple-600 opacity-90" />
              <div className="absolute inset-0 rounded-lg flex items-center justify-center">
                <Zap className="w-4 h-4 text-white" strokeWidth={2.5} />
              </div>
            </div>
            <div>
              <div className="font-bold text-white text-sm leading-tight tracking-tight">Routify</div>
              <div className="text-[10px] text-indigo-400/70 leading-tight font-medium">API Gateway</div>
            </div>
          </div>
        </div>

        {/* Connection pill */}
        <div className="px-4 pb-3">
          <div
            className={cn(
              'flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium w-fit transition-all',
              isConnected
                ? 'bg-green-500/10 text-green-400 border border-green-500/20'
                : wsStatus === 'RECONNECTING'
                  ? 'bg-yellow-500/10 text-yellow-400 border border-yellow-500/20'
                  : 'bg-white/5 text-gray-500 border border-white/[0.06]',
            )}
          >
            <span
              className={cn(
                'w-1.5 h-1.5 rounded-full',
                isConnected
                  ? 'bg-green-400 animate-pulse'
                  : wsStatus === 'RECONNECTING'
                    ? 'bg-yellow-400 animate-pulse'
                    : 'bg-gray-600',
              )}
            />
            {isConnected
              ? 'Live'
              : wsStatus === 'RECONNECTING'
                ? 'Reconnecting…'
                : wsStatus === 'CONNECTING'
                  ? 'Connecting…'
                  : 'Offline'}
          </div>
        </div>

        {/* Current workspace badge */}
        {currentTenant && (
          <div className="px-4 pb-3">
            <div className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-indigo-500/[0.06] border border-indigo-500/15">
              <Building2 className="w-3 h-3 text-indigo-400 shrink-0" />
              <div className="min-w-0 flex-1">
                <div className="text-[11px] font-semibold text-indigo-300 truncate">{currentTenant.name}</div>
                <div className="text-[9px] text-indigo-400/50 font-medium">{currentTenant.slug}</div>
              </div>
            </div>
          </div>
        )}

        <div className="mx-4 h-px bg-white/[0.06] mb-2" />

        {/* Navigation */}
        <nav className="flex-1 px-2 py-1 space-y-0.5">
          <p className="px-3 pt-1 pb-2 text-[10px] font-semibold text-gray-600 uppercase tracking-widest">Navigation</p>
          {NAV_ITEMS.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                cn(
                  'relative flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-all duration-150 group',
                  isActive
                    ? 'bg-indigo-500/10 text-indigo-300'
                    : 'text-gray-400 hover:text-gray-200 hover:bg-white/[0.04]',
                )
              }
            >
              {({ isActive }) => (
                <>
                  {isActive && (
                    <span className="absolute left-0 top-1/2 -translate-y-1/2 w-0.5 h-4 bg-indigo-500 rounded-full" />
                  )}
                  <Icon
                    className={cn(
                      'w-4 h-4 shrink-0 transition-colors',
                      isActive ? 'text-indigo-400' : 'text-gray-500 group-hover:text-gray-400',
                    )}
                  />
                  <span className={cn('font-medium', isActive ? 'text-indigo-200' : '')}>{label}</span>
                </>
              )}
            </NavLink>
          ))}

          {/* Super Admin section */}
          {isSuperAdmin && (
            <>
              <div className="mx-1 my-2 h-px bg-white/[0.05]" />
              <p className="px-3 pb-1.5 text-[10px] font-semibold text-red-500/60 uppercase tracking-widest">
                Super Admin
              </p>
              {SUPER_ADMIN_NAV_ITEMS.map(({ to, label, icon: Icon }) => (
                <NavLink
                  key={to}
                  to={to}
                  className={({ isActive }) =>
                    cn(
                      'relative flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-all duration-150 group',
                      isActive
                        ? 'bg-red-500/10 text-red-300'
                        : 'text-gray-400 hover:text-gray-200 hover:bg-white/[0.04]',
                    )
                  }
                >
                  {({ isActive }) => (
                    <>
                      {isActive && (
                        <span className="absolute left-0 top-1/2 -translate-y-1/2 w-0.5 h-4 bg-red-500 rounded-full" />
                      )}
                      <Icon
                        className={cn(
                          'w-4 h-4 shrink-0 transition-colors',
                          isActive ? 'text-red-400' : 'text-gray-500 group-hover:text-gray-400',
                        )}
                      />
                      <span className={cn('font-medium', isActive ? 'text-red-200' : '')}>{label}</span>
                    </>
                  )}
                </NavLink>
              ))}
            </>
          )}
        </nav>

        {/* Live event mini-feed */}
        {recentEvents.length > 0 && (
          <div className="mx-4 mb-3 rounded-lg bg-white/[0.02] border border-white/[0.05] p-3">
            <div className="flex items-center gap-1.5 text-[10px] text-gray-500 uppercase tracking-wider font-medium mb-2">
              <Activity className="w-3 h-3" />
              Live events
            </div>
            <div className="space-y-1.5">
              {recentEvents.slice(0, 3).map((e) => (
                <div key={e.id} className="flex items-center gap-1.5 text-[11px] text-gray-500 truncate">
                  <span className="w-1 h-1 rounded-full bg-indigo-500 shrink-0" />
                  {e.label}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* User section */}
        <div className="mx-4 mb-4 rounded-lg bg-white/[0.03] border border-white/[0.06] p-3">
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 rounded-full bg-gradient-to-br from-indigo-500/40 to-purple-600/40 border border-indigo-500/20 flex items-center justify-center text-xs font-bold text-indigo-300 shrink-0">
              {initials}
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-xs font-semibold text-white truncate">{user?.username ?? '—'}</div>
              <div className="text-[10px] text-gray-500 truncate">{user?.role?.replace('_', ' ') ?? '—'}</div>
            </div>
            <button
              onClick={handleLogout}
              title="Logout"
              className="p-1.5 rounded-md text-gray-500 hover:text-red-400 hover:bg-red-500/10 transition-colors shrink-0"
            >
              <LogOut className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 flex flex-col overflow-hidden bg-[#080a0f]">
        <ErrorBoundary>
          <Outlet />
        </ErrorBoundary>
      </main>
    </div>
  )
}
