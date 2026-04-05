import React, { useState, useRef, useEffect } from 'react'
import { useAuth } from './useAuth'
import { useQuery } from '@tanstack/react-query'
import { tenantsApi } from '../../api/tenantsApi'
import {
  Zap,
  AlertCircle,
  Eye,
  EyeOff,
  ArrowRight,
  Loader2,
  ShieldCheck,
  Gauge,
  GitBranch,
  ChevronDown,
  Search,
  Building2,
} from 'lucide-react'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'

const FEATURES = [
  { icon: Zap, label: 'Zero Downtime', desc: 'Routes activate instantly via Kafka — no restarts' },
  { icon: ShieldCheck, label: 'Filter Chain', desc: 'JWT, API Key, Rate Limit, Transform and more' },
  { icon: Gauge, label: 'Live Dashboard', desc: 'Real-time events via Server-Sent Events' },
  { icon: GitBranch, label: 'Hot Reload', desc: 'Dynamic routing without gateway restart' },
]

// ─── Workspace Dropdown ───────────────────────────────────────────────────────

interface WorkspaceDropdownProps {
  value: string
  onChange: (slug: string) => void
  inputCls: string
}

function WorkspaceDropdown({ value, onChange, inputCls }: WorkspaceDropdownProps) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)

  const { data: workspaces = [], isLoading } = useQuery({
    queryKey: ['workspaces'],
    queryFn: tenantsApi.listWorkspaces,
    staleTime: 5 * 60_000,
  })

  const filtered = workspaces.filter(
    (w) => w.name.toLowerCase().includes(search.toLowerCase()) || w.slug.toLowerCase().includes(search.toLowerCase()),
  )

  const selected = workspaces.find((w) => w.slug === value)

  // Close on outside click
  useEffect(() => {
    function handler(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={`${inputCls} flex items-center justify-between gap-2 text-left`}
      >
        <span className="flex items-center gap-2 min-w-0">
          <Building2 className="w-3.5 h-3.5 text-gray-500 shrink-0" />
          {isLoading ? (
            <span className="text-gray-600">Loading workspaces…</span>
          ) : selected ? (
            <span className="truncate">
              {selected.name} <span className="text-gray-500">({selected.slug})</span>
            </span>
          ) : (
            <span className="text-gray-600">{value || 'Select workspace…'}</span>
          )}
        </span>
        <ChevronDown
          className={`w-3.5 h-3.5 text-gray-500 shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {open && (
        <div className="absolute z-50 left-0 right-0 top-full mt-1.5 bg-[#13151f] border border-white/[0.09] rounded-xl shadow-xl overflow-hidden">
          {/* Search */}
          <div className="flex items-center gap-2 px-3 py-2 border-b border-white/[0.07]">
            <Search className="w-3.5 h-3.5 text-gray-500 shrink-0" />
            <input
              type="text"
              autoFocus
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search workspaces…"
              className="flex-1 bg-transparent text-sm text-white placeholder-gray-600 outline-none"
            />
          </div>
          {/* Options */}
          <div className="max-h-48 overflow-y-auto py-1">
            {filtered.length === 0 ? (
              <p className="px-4 py-3 text-sm text-gray-600">No workspaces found.</p>
            ) : (
              filtered.map((w) => (
                <button
                  key={w.slug}
                  type="button"
                  onClick={() => {
                    onChange(w.slug)
                    setOpen(false)
                    setSearch('')
                  }}
                  className={`w-full flex items-center gap-2.5 px-4 py-2.5 text-left text-sm transition-colors ${
                    w.slug === value ? 'bg-indigo-500/10 text-indigo-300' : 'text-gray-300 hover:bg-white/[0.04]'
                  }`}
                >
                  <Building2 className="w-3.5 h-3.5 text-gray-500 shrink-0" />
                  <span className="flex-1 truncate">{w.name}</span>
                  <code className="text-[11px] text-gray-500 font-mono">{w.slug}</code>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Login Page ───────────────────────────────────────────────────────────────

export default function LoginPage() {
  useDocumentTitle('Login')
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [tenantSlug, setTenantSlug] = useState('')
  const [showPass, setShowPass] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await login(username, password, tenantSlug)
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { detail?: string } } })?.response?.data?.detail
      setError(msg ?? 'Invalid credentials. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  const inputCls =
    'w-full bg-white/[0.05] border border-white/[0.09] rounded-xl px-4 py-3 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/20 transition-all'

  return (
    <div className="min-h-screen flex bg-[#080a0f]">
      {/* Left panel */}
      <div className="hidden lg:flex flex-col w-[460px] bg-[#0c0e14] border-r border-white/[0.06] p-10 relative overflow-hidden shrink-0">
        {/* Ambient glows */}
        <div className="absolute -top-32 -left-32 w-80 h-80 bg-indigo-600/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute -bottom-32 -right-32 w-80 h-80 bg-purple-600/8 rounded-full blur-3xl pointer-events-none" />

        {/* Logo */}
        <div className="flex items-center gap-3 relative z-10">
          <div className="relative w-10 h-10">
            <div className="absolute inset-0 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600" />
            <div className="absolute inset-0 rounded-xl flex items-center justify-center">
              <Zap className="w-5 h-5 text-white" strokeWidth={2.5} />
            </div>
          </div>
          <div>
            <span className="text-lg font-bold text-white tracking-tight">Routify</span>
            <p className="text-[11px] text-indigo-400/70 leading-none mt-0.5">API Gateway Platform v2</p>
          </div>
        </div>

        {/* Hero text */}
        <div className="mt-auto relative z-10">
          <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-indigo-500/10 border border-indigo-500/20 text-xs text-indigo-400 font-semibold mb-6">
            <span className="w-1.5 h-1.5 rounded-full bg-indigo-400 animate-pulse" />
            Spring Cloud Gateway
          </div>
          <h2 className="text-3xl font-bold text-white leading-tight mb-4 tracking-tight">
            Zero-downtime API
            <br />
            routing, your way.
          </h2>
          <p className="text-gray-500 text-sm leading-relaxed mb-8">
            Create routes, attach auth/rate-limit/transform filters, and activate them instantly with zero downtime —
            powered by Kafka hot-reload.
          </p>
          <div className="space-y-3">
            {FEATURES.map((f) => (
              <div
                key={f.label}
                className="flex items-center gap-3 p-3 rounded-xl bg-white/[0.03] border border-white/[0.05]"
              >
                <div className="w-7 h-7 rounded-lg bg-indigo-500/15 border border-indigo-500/20 flex items-center justify-center shrink-0">
                  <f.icon className="w-3.5 h-3.5 text-indigo-400" />
                </div>
                <div>
                  <p className="text-xs font-semibold text-gray-300">{f.label}</p>
                  <p className="text-[11px] text-gray-600 mt-0.5">{f.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Right — login form */}
      <div className="flex-1 flex items-center justify-center p-6">
        <div className="w-full max-w-[400px]">
          {/* Mobile logo */}
          <div className="flex lg:hidden items-center gap-3 mb-8 justify-center">
            <div className="relative w-9 h-9">
              <div className="absolute inset-0 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600" />
              <div className="absolute inset-0 rounded-xl flex items-center justify-center">
                <Zap className="w-4 h-4 text-white" strokeWidth={2.5} />
              </div>
            </div>
            <span className="text-lg font-bold text-white">Routify</span>
          </div>

          {/* Heading */}
          <div className="mb-8">
            <h1 className="text-2xl font-bold text-white tracking-tight mb-1.5">Welcome back</h1>
            <p className="text-gray-500 text-sm">Sign in to your workspace to continue.</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            {/* Workspace dropdown */}
            <div className="space-y-1.5">
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Workspace</label>
              <WorkspaceDropdown value={tenantSlug} onChange={setTenantSlug} inputCls={inputCls} />
              <p className="text-[11px] text-gray-600 pl-1">Select your organization's workspace</p>
            </div>

            {/* Username */}
            <div className={`space-y-1.5 transition-opacity ${!tenantSlug ? 'opacity-40 pointer-events-none' : ''}`}>
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Username</label>
              <input
                type="text"
                required
                autoFocus
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                disabled={!tenantSlug}
                className={inputCls}
                placeholder="admin"
              />
            </div>

            {/* Password */}
            <div className={`space-y-1.5 transition-opacity ${!tenantSlug ? 'opacity-40 pointer-events-none' : ''}`}>
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">Password</label>
              <div className="relative">
                <input
                  type={showPass ? 'text' : 'password'}
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={!tenantSlug}
                  className={`${inputCls} pr-12`}
                  placeholder="••••••••"
                />
                <button
                  type="button"
                  onClick={() => setShowPass((v) => !v)}
                  disabled={!tenantSlug}
                  className="absolute right-3 top-1/2 -translate-y-1/2 p-1 rounded-lg text-gray-500 hover:text-gray-300 transition-colors"
                >
                  {showPass ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            {/* Error */}
            {error && (
              <div className="flex items-start gap-3 p-3.5 rounded-xl bg-red-500/[0.08] border border-red-500/20">
                <AlertCircle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
                <p className="text-red-300 text-sm">{error}</p>
              </div>
            )}

            {/* Submit */}
            <button
              type="submit"
              disabled={loading || !tenantSlug}
              className="w-full flex items-center justify-center gap-2 py-3 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-bold rounded-xl transition-all shadow-lg shadow-indigo-500/25 hover:shadow-indigo-500/35 mt-2"
            >
              {loading ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin" /> Signing in…
                </>
              ) : (
                <>
                  Sign in <ArrowRight className="w-4 h-4" />
                </>
              )}
            </button>
          </form>

          <p className="text-center text-[11px] text-gray-600 mt-6">
            Default username: <code className="text-gray-500 bg-white/[0.04] px-1 py-0.5 rounded">admin</code>
          </p>
        </div>
      </div>
    </div>
  )
}
