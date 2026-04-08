/**
 * Select — a fully-styled custom dropdown component for Routify Dashboard.
 *
 * Features:
 *  - Dark-theme consistent styling (matches FilterTypePicker)
 *  - Optional grouped options via `group` property
 *  - Optional inline search (enabled when `searchable` prop is true or option count ≥ 6)
 *  - Chevron rotate animation on open
 *  - Backdrop overlay to close on outside click
 *  - `animate-fade-in` open transition
 */
import { useState, useRef, useEffect } from 'react'
import { ChevronDown, Check, Search } from 'lucide-react'
import { cn } from '../../lib/utils'

export interface SelectOption {
  value: string
  label: string
  description?: string
  group?: string
  icon?: React.ReactNode
  disabled?: boolean
}

interface SelectProps {
  value: string
  options: SelectOption[]
  onChange: (value: string) => void
  placeholder?: string
  /** Force the search bar on or off. Auto-enabled when options.length >= 6 */
  searchable?: boolean
  disabled?: boolean
  className?: string
  /** Render inside a portal-less container; use when overflow-hidden parent clips the dropdown */
  id?: string
}

export function Select({
  value,
  options,
  onChange,
  placeholder = 'Select…',
  searchable,
  disabled,
  className,
}: SelectProps) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)

  const showSearch = searchable !== undefined ? searchable : options.length >= 6

  const selected = options.find((o) => o.value === value)

  // Filter options by search
  const filtered = search
    ? options.filter(
        (o) =>
          o.label.toLowerCase().includes(search.toLowerCase()) ||
          o.description?.toLowerCase().includes(search.toLowerCase()) ||
          o.group?.toLowerCase().includes(search.toLowerCase()),
      )
    : options

  // Group the filtered options
  const hasGroups = filtered.some((o) => o.group)
  const groups: { label: string | null; items: SelectOption[] }[] = hasGroups
    ? Object.entries(
        filtered.reduce<Record<string, SelectOption[]>>((acc, o) => {
          const g = o.group ?? ''
          ;(acc[g] ??= []).push(o)
          return acc
        }, {}),
      ).map(([label, items]) => ({ label: label || null, items }))
    : [{ label: null, items: filtered }]

  // Close on outside click
  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
        setSearch('')
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  const handleSelect = (opt: SelectOption) => {
    if (opt.disabled) return
    onChange(opt.value)
    setOpen(false)
    setSearch('')
  }

  return (
    <div ref={containerRef} className={cn('relative', className)}>
      {/* Trigger */}
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen((o) => !o)}
        className={cn(
          'w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg border text-sm transition-all',
          open
            ? 'bg-white/[0.06] border-indigo-500 text-white ring-1 ring-indigo-500/30'
            : 'bg-white/[0.04] border-white/[0.08] text-white hover:border-white/20',
          disabled && 'opacity-50 cursor-not-allowed pointer-events-none',
        )}
      >
        <span className={cn('flex items-center gap-2 min-w-0', !selected && 'text-gray-500')}>
          {selected?.icon && <span className="shrink-0">{selected.icon}</span>}
          <span className="truncate">{selected ? selected.label : placeholder}</span>
        </span>
        <ChevronDown
          className={cn('w-4 h-4 shrink-0 text-gray-500 transition-transform duration-200', open && 'rotate-180')}
        />
      </button>

      {/* Dropdown panel */}
      {open && (
        <div
          className={cn(
            'absolute z-50 mt-1 w-full min-w-[160px] rounded-xl border border-white/[0.10]',
            'bg-[#111318] shadow-2xl shadow-black/60 overflow-hidden animate-fade-in',
          )}
        >
          {showSearch && (
            <div className="p-2 border-b border-white/[0.06] bg-[#111318] sticky top-0">
              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-600 pointer-events-none" />
                <input
                  // eslint-disable-next-line jsx-a11y/no-autofocus
                  autoFocus
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search…"
                  className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 transition-all"
                />
              </div>
            </div>
          )}

          <div className="max-h-64 overflow-y-auto">
            {filtered.length === 0 && (
              <p className="px-4 py-5 text-sm text-gray-500 text-center">
                {search ? `No results for "${search}"` : 'No options available'}
              </p>
            )}

            {groups.map((group, gi) => (
              <div key={gi}>
                {group.label && (
                  <div className="px-3 py-1.5 text-[10px] font-bold text-gray-600 uppercase tracking-widest bg-white/[0.02] sticky top-0">
                    {group.label}
                  </div>
                )}
                {group.items.map((opt) => {
                  const isSelected = opt.value === value
                  return (
                    <button
                      key={opt.value}
                      type="button"
                      disabled={opt.disabled}
                      onClick={() => handleSelect(opt)}
                      className={cn(
                        'w-full flex items-center gap-2.5 px-3 py-2.5 text-left text-sm transition-colors',
                        isSelected
                          ? 'bg-indigo-500/10 text-indigo-200'
                          : opt.disabled
                            ? 'opacity-40 cursor-not-allowed text-gray-400'
                            : 'text-gray-200 hover:bg-white/[0.05]',
                      )}
                    >
                      {opt.icon && <span className="shrink-0 text-gray-400">{opt.icon}</span>}
                      <span className="flex-1 min-w-0">
                        <span className="block truncate font-medium">{opt.label}</span>
                        {opt.description && (
                          <span className="block text-[11px] text-gray-500 mt-0.5 leading-snug truncate">
                            {opt.description}
                          </span>
                        )}
                      </span>
                      {isSelected && <Check className="w-3.5 h-3.5 text-indigo-400 shrink-0" />}
                    </button>
                  )
                })}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
