/**
 * GatewayPrimitives — Shared low-level UI building blocks for all gateway tabs.
 *
 * Kept gateway-scoped (not promoted to /ui/) because the specific styling
 * decisions (input chrome, toggle sizing, save-bar persistence copy) are
 * intentional to this domain.
 */
import React from 'react'
import { Save, Server } from 'lucide-react'
import { cn } from '../../../lib/utils'

// Re-export cn so tab files can import it from here alongside primitives
export { cn }

// ─── Section heading with icon support ────────────────────────────────────────

export function SectionHeader({
  title,
  description,
  icon: Icon,
  badge,
  actions,
}: {
  title: string
  description?: string
  icon?: React.ComponentType<{ className?: string }>
  badge?: React.ReactNode
  actions?: React.ReactNode
}) {
  return (
    <div className="flex items-start justify-between gap-4 mb-6">
      <div className="flex items-start gap-3">
        {Icon && (
          <div className="mt-0.5 shrink-0 w-8 h-8 rounded-lg bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center">
            <Icon className="w-4 h-4 text-indigo-400" />
          </div>
        )}
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-base font-semibold text-white">{title}</h2>
            {badge}
          </div>
          {description && (
            <p className="text-sm text-gray-400 mt-0.5 leading-relaxed">{description}</p>
          )}
        </div>
      </div>
      {actions && <div className="shrink-0">{actions}</div>}
    </div>
  )
}

// ─── Sub-section divider with optional label ──────────────────────────────────

export function SubSection({
  label,
  icon: Icon,
  className,
  children,
}: {
  label: string
  icon?: React.ComponentType<{ className?: string }>
  className?: string
  children?: React.ReactNode
}) {
  return (
    <div className={cn('pt-5 pb-2', className)}>
      <div className="flex items-center gap-2 mb-3">
        {Icon && <Icon className="w-3.5 h-3.5 text-gray-500" />}
        <span className="text-[11px] font-bold text-gray-500 uppercase tracking-widest">{label}</span>
        {children && <div className="ml-auto">{children}</div>}
      </div>
      <div className="h-px bg-white/[0.05]" />
    </div>
  )
}

// ─── Toggle row ───────────────────────────────────────────────────────────────

export function ToggleRow({
  label,
  description,
  checked,
  onChange,
  disabled,
  danger,
}: {
  label: string
  description?: string
  checked: boolean
  onChange: (v: boolean) => void
  disabled?: boolean
  danger?: boolean
}) {
  return (
    <div className={cn(
      'flex items-center justify-between py-3 border-b border-white/[0.05] last:border-0',
      disabled && 'opacity-50',
    )}>
      <div className="mr-4 min-w-0">
        <div className="text-sm text-white">{label}</div>
        {description && (
          <div className="text-xs text-gray-500 mt-0.5 leading-relaxed">{description}</div>
        )}
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => !disabled && onChange(!checked)}
        disabled={disabled}
        className={cn(
          'relative shrink-0 w-9 h-5 rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0a0c10]',
          checked
            ? danger ? 'bg-red-600 focus-visible:ring-red-500' : 'bg-indigo-600 focus-visible:ring-indigo-500'
            : 'bg-gray-700 focus-visible:ring-gray-500',
          disabled && 'cursor-not-allowed',
        )}
      >
        <span className={cn(
          'absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform duration-150',
          checked ? 'translate-x-4' : 'translate-x-0',
        )} />
      </button>
    </div>
  )
}

// ─── Labelled field wrapper ───────────────────────────────────────────────────

export function Field({
  label,
  hint,
  optional,
  children,
}: {
  label: string
  hint?: string
  optional?: boolean
  children: React.ReactNode
}) {
  return (
    <div>
      <div className="flex items-center gap-2 mb-1">
        <label className="block text-xs font-medium text-gray-400">{label}</label>
        {optional && <span className="text-[10px] text-gray-600">optional</span>}
      </div>
      {children}
      {hint && <p className="text-xs text-gray-600 mt-1 leading-relaxed">{hint}</p>}
    </div>
  )
}

// ─── Shared input / textarea class strings ────────────────────────────────────

export const inputCls =
  'w-full bg-white/[0.04] border border-white/10 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all placeholder-gray-600'

export const monoInputCls = `${inputCls} font-mono text-xs`

export const textareaCls = `${inputCls} resize-none`

// ─── Sticky save bar ──────────────────────────────────────────────────────────

export function SaveBar({
  onSave,
  isPending,
  dirty,
  label = 'Save & Apply to Gateway',
}: {
  onSave: () => void
  isPending: boolean
  dirty: boolean
  label?: string
}) {
  if (!dirty) return null
  return (
    <div className="sticky bottom-0 mt-8 bg-[#0a0c10]/95 backdrop-blur border-t border-white/10 px-6 py-3 flex items-center justify-between gap-4 z-10">
      <div className="flex items-center gap-2 text-xs text-gray-500">
        <Server className="w-3.5 h-3.5 text-emerald-400" />
        <span>
          Persists to <strong className="text-emerald-400">PostgreSQL</strong> and broadcasts via{' '}
          <strong className="text-indigo-400">Kafka</strong> — all pods reload within seconds
        </span>
      </div>
      <button
        onClick={onSave}
        disabled={isPending}
        className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors shadow-lg shadow-indigo-500/20"
      >
        <Save className={cn('w-4 h-4', isPending && 'animate-spin')} />
        {isPending ? 'Saving…' : label}
      </button>
    </div>
  )
}

// ─── Inline dirty save button ─────────────────────────────────────────────────

export function InlineSaveButton({
  onSave,
  isPending,
  dirty,
  label,
}: {
  onSave: () => void
  isPending: boolean
  dirty: boolean
  label: string
}) {
  if (!dirty) return null
  return (
    <div className="mt-5">
      <button
        onClick={onSave}
        disabled={isPending}
        className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors shadow-lg shadow-indigo-500/20"
      >
        <Save className={cn('w-4 h-4', isPending && 'animate-spin')} />
        {isPending ? 'Saving…' : label}
      </button>
    </div>
  )
}

// ─── Status badge ─────────────────────────────────────────────────────────────

import { CheckCircle, XCircle, AlertTriangle, Activity } from 'lucide-react'

export function StatusBadge({ state }: { state: string | null | undefined }) {
  const normalized = (state ?? 'UNKNOWN').toUpperCase()
  const cfg = {
    CLOSED:    { color: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20', icon: CheckCircle, label: 'Closed' },
    OPEN:      { color: 'text-red-400 bg-red-400/10 border-red-400/20',             icon: XCircle,     label: 'Open' },
    HALF_OPEN: { color: 'text-amber-400 bg-amber-400/10 border-amber-400/20',       icon: AlertTriangle, label: 'Half-Open' },
    UP:        { color: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20', icon: CheckCircle, label: 'UP' },
    DOWN:      { color: 'text-red-400 bg-red-400/10 border-red-400/20',             icon: XCircle,     label: 'DOWN' },
    UNKNOWN:   { color: 'text-gray-400 bg-gray-400/10 border-gray-400/20',          icon: Activity,    label: 'Unknown' },
  }[normalized] ?? { color: 'text-gray-400 bg-gray-400/10 border-gray-400/20', icon: Activity, label: normalized }

  return (
    <span className={cn(
      'inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full font-medium border',
      cfg.color,
    )}>
      <cfg.icon className="w-3 h-3" />
      {cfg.label}
    </span>
  )
}

// ─── Empty state ──────────────────────────────────────────────────────────────

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon?: React.ComponentType<{ className?: string }>
  title: string
  description?: string
  action?: React.ReactNode
}) {
  return (
    <div className="rounded-xl border border-dashed border-white/10 bg-white/[0.015] py-10 flex flex-col items-center gap-3 text-center">
      {Icon && (
        <div className="w-10 h-10 rounded-xl bg-white/[0.04] border border-white/[0.06] flex items-center justify-center">
          <Icon className="w-5 h-5 text-gray-500" />
        </div>
      )}
      <div>
        <p className="text-sm font-medium text-gray-400">{title}</p>
        {description && (
          <p className="text-xs text-gray-600 mt-1 leading-relaxed max-w-xs mx-auto">{description}</p>
        )}
      </div>
      {action}
    </div>
  )
}

// ─── Info / warning banners ───────────────────────────────────────────────────

export function InfoBanner({ children, variant = 'info' }: {
  children: React.ReactNode
  variant?: 'info' | 'warning' | 'danger' | 'success'
}) {
  const styles = {
    info:    'bg-indigo-500/5 border-indigo-500/20 text-indigo-300/80',
    warning: 'bg-amber-500/[0.07] border-amber-500/20 text-amber-300/90',
    danger:  'bg-red-500/[0.07] border-red-500/20 text-red-300/90',
    success: 'bg-emerald-500/[0.07] border-emerald-500/20 text-emerald-300/90',
  }
  return (
    <div className={cn('rounded-xl border px-4 py-3 text-xs leading-relaxed', styles[variant])}>
      {children}
    </div>
  )
}

// ─── Card wrapper ─────────────────────────────────────────────────────────────

export function Card({
  children,
  className,
  padded = true,
}: {
  children: React.ReactNode
  className?: string
  padded?: boolean
}) {
  return (
    <div className={cn(
      'bg-white/[0.03] rounded-xl border border-white/[0.06]',
      padded && 'p-5',
      className,
    )}>
      {children}
    </div>
  )
}

// ─── KPI stat tile ────────────────────────────────────────────────────────────

export function StatTile({
  label,
  value,
  sub,
  icon: Icon,
  color = 'indigo',
}: {
  label: string
  value: React.ReactNode
  sub?: React.ReactNode
  icon?: React.ComponentType<{ className?: string }>
  color?: 'indigo' | 'emerald' | 'amber' | 'rose' | 'sky'
}) {
  const colors = {
    indigo:  { bg: 'bg-indigo-500/10',  border: 'border-indigo-500/20',  icon: 'text-indigo-400' },
    emerald: { bg: 'bg-emerald-500/10', border: 'border-emerald-500/20', icon: 'text-emerald-400' },
    amber:   { bg: 'bg-amber-500/10',   border: 'border-amber-500/20',   icon: 'text-amber-400' },
    rose:    { bg: 'bg-rose-500/10',    border: 'border-rose-500/20',    icon: 'text-rose-400' },
    sky:     { bg: 'bg-sky-500/10',     border: 'border-sky-500/20',     icon: 'text-sky-400' },
  }[color]

  return (
    <div className="bg-white/[0.03] rounded-xl border border-white/[0.06] p-5 flex items-start gap-4">
      {Icon && (
        <div className={cn('mt-0.5 shrink-0 w-9 h-9 rounded-lg border flex items-center justify-center', colors.bg, colors.border)}>
          <Icon className={cn('w-4.5 h-4.5', colors.icon)} />
        </div>
      )}
      <div className="min-w-0">
        <div className="text-[10px] font-bold text-gray-500 uppercase tracking-widest">{label}</div>
        <div className="text-2xl font-bold text-white mt-0.5">{value}</div>
        {sub && <div className="text-xs text-gray-500 mt-0.5">{sub}</div>}
      </div>
    </div>
  )
}

// ─── Linked filter count badge ────────────────────────────────────────────────

import { Link } from 'lucide-react'

export function LinkedBadge({
  count,
  names,
}: {
  count: number
  names: string[]
}) {
  if (count === 0) return null
  return (
    <span
      title={`Used by: ${names.join(', ')}`}
      className="flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-full bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 whitespace-nowrap"
    >
      <Link className="w-2.5 h-2.5" />
      {count} filter{count !== 1 ? 's' : ''}
    </span>
  )
}

