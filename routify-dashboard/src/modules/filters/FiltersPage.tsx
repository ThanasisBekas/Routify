/**
 * FiltersPage — redesigned filters management page.
 *
 * Layout:
 *  - Stats bar (total / active / in-use / categories)
 *  - Category tab strip (All + one per active category)
 *  - Searchable card grid
 *  - Slide-over drawer for create / edit
 */
import { useState, useMemo } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Filter, Plus, Search, Pencil, Trash2, Loader2, ChevronRight, Layers } from 'lucide-react'
import { toast } from 'sonner'
import { filtersApi } from '../../api/filtersApi'
import FilterDefinitionForm from './FilterDefinitionForm'
import type { FilterSummary } from '../../types'
import { cn } from '../../lib/utils'
import { useRealtimeQuery } from '../../hooks/useRealtimeQuery'
import {
  FILTER_REGISTRY,
  CATEGORY_ORDER,
  CATEGORY_COLORS,
  getFilterEntry,
  type FilterCategory,
} from './filterRegistry'

// ─── Stats bar ────────────────────────────────────────────────────────────────

function StatsBar({ filters }: { filters: FilterSummary[] }) {
  const total    = filters.length
  const active   = filters.filter(f => f.enabled).length
  const inUse    = filters.filter(f => f.usageCount > 0).length
  const cats     = new Set(filters.map(f => getFilterEntry(f.filterType)?.category ?? 'Other')).size

  const stats = [
    { label: 'Total',          value: total,   color: 'text-white' },
    { label: 'Active',         value: active,  color: 'text-emerald-400' },
    { label: 'In Use',         value: inUse,   color: 'text-indigo-400' },
    { label: 'Categories',     value: cats,    color: 'text-gray-400' },
  ]

  return (
    <div className="flex items-center gap-0 divide-x divide-white/[0.06] border-b border-white/[0.06] bg-white/[0.01] shrink-0">
      {stats.map(s => (
        <div key={s.label} className="flex flex-col items-center px-6 py-3 gap-0.5">
          <span className={cn('text-xl font-bold tabular-nums', s.color)}>{s.value}</span>
          <span className="text-[10px] text-gray-600 uppercase tracking-widest font-semibold">{s.label}</span>
        </div>
      ))}
    </div>
  )
}

// ─── Category tabs ────────────────────────────────────────────────────────────

function CategoryTabs({
  filters,
  active,
  onChange,
}: {
  filters: FilterSummary[]
  active: FilterCategory | 'All'
  onChange: (cat: FilterCategory | 'All') => void
}) {
  // Derive which categories actually have filters
  const usedCats = useMemo(() => {
    const s = new Set<FilterCategory>()
    filters.forEach(f => {
      const cat = getFilterEntry(f.filterType)?.category
      if (cat) s.add(cat)
    })
    return CATEGORY_ORDER.filter(c => s.has(c))
  }, [filters])

  const tabs: (FilterCategory | 'All')[] = ['All', ...usedCats]

  return (
    <div className="flex items-center gap-1 px-6 py-2.5 border-b border-white/[0.06] overflow-x-auto scrollbar-none shrink-0">
      {tabs.map(tab => (
        <button
          key={tab}
          onClick={() => onChange(tab)}
          className={cn(
            'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold whitespace-nowrap transition-all',
            active === tab
              ? tab === 'All'
                ? 'bg-indigo-600/30 border border-indigo-500/40 text-indigo-300'
                : `border ${CATEGORY_COLORS[tab as FilterCategory]} bg-opacity-10`
              : 'text-gray-500 hover:text-gray-300 border border-transparent hover:border-white/[0.06]',
            active === tab && tab !== 'All' && 'border',
          )}
        >
          {tab === 'All' ? (
            <><Layers className="w-3 h-3 shrink-0" /> All</>
          ) : (
            tab
          )}
          <span className={cn(
            'text-[10px] px-1.5 py-0.5 rounded-full font-bold',
            active === tab
              ? tab === 'All' ? 'bg-indigo-500/30 text-indigo-300' : 'bg-white/10 text-current'
              : 'bg-white/[0.04] text-gray-600',
          )}>
            {tab === 'All'
              ? filters.length
              : filters.filter(f => getFilterEntry(f.filterType)?.category === tab).length
            }
          </span>
        </button>
      ))}
    </div>
  )
}

// ─── Filter card ──────────────────────────────────────────────────────────────

function FilterCard({
  filter,
  onEdit,
  onDelete,
  isDeleting,
}: {
  filter: FilterSummary
  onEdit: () => void
  onDelete: () => void
  isDeleting: boolean
}) {
  const entry = getFilterEntry(filter.filterType)
  const category = entry?.category ?? 'Custom'
  const catColor = CATEGORY_COLORS[category as FilterCategory] ?? 'text-gray-400 bg-gray-400/10 border-gray-400/20'
  const nodeColor = entry ?? { color: 'text-gray-400', bg: 'bg-gray-400/10', border: 'border-gray-400/20' }

  const canDelete = filter.usageCount === 0 && !isDeleting

  return (
    <div className={cn(
      'group relative flex flex-col rounded-xl border bg-white/[0.02] transition-all duration-150',
      'hover:bg-white/[0.04] hover:border-white/[0.12]',
      nodeColor.border,
    )}>
      {/* Card header */}
      <div className="flex items-start justify-between gap-3 p-4 pb-3">
        <div className="flex items-start gap-3 min-w-0">
          {/* Accent icon */}
          <div className={cn(
            'w-9 h-9 rounded-xl flex items-center justify-center shrink-0 border',
            nodeColor.bg, nodeColor.border,
          )}>
            <span className={cn('text-base font-bold leading-none', nodeColor.color)}>
              {entry?.label.slice(0, 2).toUpperCase() ?? '??'}
            </span>
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-sm font-bold text-white leading-tight truncate max-w-[160px]">
                {filter.name}
              </span>
              {!filter.enabled && (
                <span className="text-[9px] font-bold px-1.5 py-0.5 rounded bg-yellow-500/15 border border-yellow-500/30 text-yellow-400">
                  DISABLED
                </span>
              )}
            </div>
            <span className={cn(
              'inline-flex items-center mt-1 text-[10px] font-bold px-2 py-0.5 rounded-full border',
              catColor,
            )}>
              {category}
            </span>
          </div>
        </div>

        {/* Action buttons — revealed on hover */}
        <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
          <button
            onClick={onEdit}
            className="p-1.5 rounded-md text-gray-500 hover:text-white hover:bg-white/[0.06] transition-colors"
            title="Edit filter"
          >
            <Pencil className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={onDelete}
            disabled={!canDelete}
            title={filter.usageCount > 0 ? 'Detach from all routes first' : 'Delete filter'}
            className="p-1.5 rounded-md text-red-400 hover:bg-red-400/10 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
          >
            {isDeleting
              ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
              : <Trash2 className="w-3.5 h-3.5" />
            }
          </button>
        </div>
      </div>

      {/* Type chip */}
      <div className="px-4 pb-3">
        <span className={cn(
          'inline-flex text-[10px] font-mono font-semibold px-2 py-0.5 rounded-md border',
          nodeColor.color, nodeColor.bg, nodeColor.border,
        )}>
          {filter.filterType}
        </span>
      </div>

      {/* Footer */}
      <div className="mt-auto flex items-center justify-end gap-2 px-4 py-2.5 border-t border-white/[0.05]">
        {/* Usage count */}
        <span className={cn(
          'flex items-center gap-1 text-[10px] px-2 py-0.5 rounded-full border shrink-0',
          filter.usageCount > 0
            ? 'text-indigo-400 bg-indigo-400/10 border-indigo-400/20'
            : 'text-gray-600 bg-white/[0.02] border-white/[0.06]',
        )}>
          {filter.usageCount} route{filter.usageCount !== 1 ? 's' : ''}
        </span>
      </div>
    </div>
  )
}

// ─── Empty state ──────────────────────────────────────────────────────────────

function EmptyState({
  search,
  category,
  onCreateClick,
}: {
  search: string
  category: FilterCategory | 'All'
  onCreateClick: () => void
}) {
  if (search || category !== 'All') {
    return (
      <div className="flex flex-col items-center justify-center py-24 gap-3">
        <div className="w-14 h-14 rounded-2xl bg-white/[0.02] border border-white/[0.06] flex items-center justify-center">
          <Search className="w-6 h-6 text-gray-600" />
        </div>
        <p className="text-sm text-gray-400">No filters match your search</p>
        <p className="text-xs text-gray-600">Try a different search term or category</p>
      </div>
    )
  }
  return (
    <div className="flex flex-col items-center justify-center py-24 gap-4">
      <div className="w-16 h-16 rounded-2xl bg-white/[0.03] border border-white/[0.06] flex items-center justify-center">
        <Filter className="w-7 h-7 text-gray-600" />
      </div>
      <div className="text-center">
        <p className="text-sm font-semibold text-gray-300 mb-1">No filter definitions yet</p>
        <p className="text-xs text-gray-600">Create reusable filters and attach them to any route</p>
      </div>
      <button
        onClick={onCreateClick}
        className="flex items-center gap-2 px-4 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-sm font-medium rounded-lg transition-all"
      >
        <Plus className="w-4 h-4" />
        Create first filter
      </button>
    </div>
  )
}

// ─── Filter type browser ──────────────────────────────────────────────────────
// Small "What's available?" panel shown when no filters exist yet

function FilterTypeBrowser({ onCreateWithType }: { onCreateWithType: (type: string) => void }) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')

  const filtered = search
    ? FILTER_REGISTRY.filter(e =>
        e.label.toLowerCase().includes(search.toLowerCase()) ||
        e.category.toLowerCase().includes(search.toLowerCase()) ||
        e.description.toLowerCase().includes(search.toLowerCase()),
      )
    : FILTER_REGISTRY

  return (
    <div className="border border-white/[0.06] rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen(v => !v)}
        className="w-full flex items-center justify-between px-4 py-3 text-left hover:bg-white/[0.02] transition-colors"
      >
        <div className="flex items-center gap-2.5">
          <Layers className="w-4 h-4 text-gray-500" />
          <span className="text-xs font-semibold text-gray-400">Available Filter Types</span>
          <span className="text-[10px] text-gray-600 bg-white/[0.04] px-1.5 py-0.5 rounded-full">{FILTER_REGISTRY.length}</span>
        </div>
        <ChevronRight className={cn('w-4 h-4 text-gray-600 transition-transform', open && 'rotate-90')} />
      </button>

      {open && (
        <div className="border-t border-white/[0.06]">
          <div className="p-3 border-b border-white/[0.04]">
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-600 pointer-events-none" />
              <input
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder="Search filter types…"
                className="w-full bg-white/[0.04] border border-white/[0.07] rounded-lg pl-8 pr-3 py-1.5 text-xs text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 transition-all"
              />
            </div>
          </div>
          <div className="max-h-72 overflow-y-auto divide-y divide-white/[0.04]">
            {filtered.map(entry => (
              <button
                key={entry.value}
                onClick={() => onCreateWithType(entry.value)}
                className="w-full flex items-center gap-3 px-4 py-2.5 text-left hover:bg-white/[0.03] transition-colors group"
              >
                <span className={cn(
                  'text-[9px] font-bold px-1.5 py-0.5 rounded-full border shrink-0',
                  CATEGORY_COLORS[entry.category],
                )}>
                  {entry.category.slice(0, 4)}
                </span>
                <div className="flex-1 min-w-0">
                  <div className="text-xs font-semibold text-gray-300 group-hover:text-white transition-colors">{entry.label}</div>
                  <div className="text-[10px] text-gray-600 truncate mt-0.5">{entry.description}</div>
                </div>
                <Plus className="w-3.5 h-3.5 text-gray-600 group-hover:text-indigo-400 transition-colors shrink-0" />
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Main page ────────────────────────────────────────────────────────────────

export default function FiltersPage() {
  const qc = useQueryClient()
  const [showForm,      setShowForm]      = useState(false)
  const [editingId,     setEditingId]     = useState<string | undefined>()
  const [presetType,    setPresetType]    = useState<string | undefined>()
  const [search,        setSearch]        = useState('')
  const [activeCategory, setActiveCategory] = useState<FilterCategory | 'All'>('All')

  const { data, isLoading } = useRealtimeQuery({
    queryKey: ['filters'],
    queryFn: () => filtersApi.list({ page: 0, size: 200 }),
    wsEvents: ['filter'],
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => filtersApi.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['filters'] })
      toast.success('Filter deleted')
    },
    onError: () => toast.error('Failed to delete filter'),
  })

  const allFilters = useMemo<FilterSummary[]>(
    () => data?.content ?? [],
    [data],
  )

  // Apply category + search filters
  const visible = useMemo(() => {
    let result = allFilters
    if (activeCategory !== 'All') {
      result = result.filter(f => getFilterEntry(f.filterType)?.category === activeCategory)
    }
    if (search.trim()) {
      const q = search.toLowerCase()
      result = result.filter(f =>
        f.name.toLowerCase().includes(q) ||
        f.filterType.toLowerCase().includes(q) ||
        (getFilterEntry(f.filterType)?.category ?? '').toLowerCase().includes(q),
      )
    }
    return result
  }, [allFilters, activeCategory, search])

  const openCreate = (preType?: string) => {
    setEditingId(undefined)
    setPresetType(preType)
    setShowForm(true)
  }

  const openEdit = (id: string) => {
    setEditingId(id)
    setPresetType(undefined)
    setShowForm(true)
  }

  const closeForm = () => {
    setShowForm(false)
    setEditingId(undefined)
    setPresetType(undefined)
  }

  const handleDelete = (f: FilterSummary) => {
    if (f.usageCount > 0) return
    if (!window.confirm(`Delete filter "${f.name}"? This cannot be undone.`)) return
    deleteMutation.mutate(f.id)
  }

  return (
    <div className="flex flex-col h-full animate-fade-in">

      {/* ── Page header ──────────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between px-6 py-5 border-b border-white/[0.06] bg-[#0c0e14] shrink-0">
        <div>
          <h1 className="text-lg font-bold text-white tracking-tight">Filters</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Reusable filter definitions · attach to any route via the workflow builder
          </p>
        </div>
        <button
          onClick={() => openCreate()}
          className="flex items-center gap-2 px-3.5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20"
        >
          <Plus className="w-4 h-4" />
          New Filter
        </button>
      </div>

      {/* ── Stats bar ────────────────────────────────────────────────────────── */}
      {!isLoading && allFilters.length > 0 && (
        <StatsBar filters={allFilters} />
      )}

      {/* ── Category tabs ─────────────────────────────────────────────────── */}
      {!isLoading && allFilters.length > 0 && (
        <CategoryTabs
          filters={allFilters}
          active={activeCategory}
          onChange={setActiveCategory}
        />
      )}

      {/* ── Search bar ───────────────────────────────────────────────────────── */}
      {!isLoading && allFilters.length > 0 && (
        <div className="px-6 py-3 border-b border-white/[0.06] shrink-0">
          <div className="relative max-w-sm">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-600 pointer-events-none" />
            <input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search by name, type, or category…"
              className="w-full bg-white/[0.04] border border-white/[0.07] rounded-lg pl-9 pr-3 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/20 transition-all"
            />
          </div>
        </div>
      )}

      {/* ── Content ──────────────────────────────────────────────────────────── */}
      <div className="flex-1 overflow-auto">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center py-24 gap-3">
            <div className="w-7 h-7 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
            <p className="text-sm text-gray-500">Loading filters…</p>
          </div>
        ) : allFilters.length === 0 ? (
          <div className="p-6 space-y-6">
            <EmptyState search="" category="All" onCreateClick={() => openCreate()} />
            <div className="max-w-2xl mx-auto">
              <FilterTypeBrowser onCreateWithType={type => openCreate(type)} />
            </div>
          </div>
        ) : visible.length === 0 ? (
          <div className="p-6">
            <EmptyState search={search} category={activeCategory} onCreateClick={() => openCreate()} />
          </div>
        ) : (
          <div className="p-6">
            {/* Card grid */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
              {visible.map(f => (
                <FilterCard
                  key={f.id}
                  filter={f}
                  onEdit={() => openEdit(f.id)}
                  onDelete={() => handleDelete(f)}
                  isDeleting={deleteMutation.isPending && deleteMutation.variables === f.id}
                />
              ))}
            </div>

            {/* Filter type browser at bottom for discoverability */}
            <div className="mt-8 max-w-2xl">
              <FilterTypeBrowser onCreateWithType={type => openCreate(type)} />
            </div>
          </div>
        )}
      </div>

      {/* ── Create / Edit form ────────────────────────────────────────────────── */}
      {showForm && (
        <FilterDefinitionForm
          editingId={editingId}
          presetFilterType={presetType}
          onClose={closeForm}
          onSaved={() => {
            closeForm()
            qc.invalidateQueries({ queryKey: ['filters'] })
          }}
        />
      )}
    </div>
  )
}

