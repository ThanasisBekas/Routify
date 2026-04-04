/**
 * AddFilterPanel.tsx — Floating "Add Filter" dropdown panel.
 *
 * Provides a searchable list of filter definitions from the backend,
 * grouped by category, with phase (PRE/POST) and order controls.
 *
 * When no matching filter definitions exist, shows a "Create & Attach" shortcut
 * that opens the filter creation form pre-seeded with the searched type,
 * then automatically attaches after successful creation.
 */
import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Search, ExternalLink } from 'lucide-react'
import { filtersApi } from '../../../api/filtersApi'
import type { FilterSummary, AttachFilterRequest } from '../../../types'
import { cn } from '../../../lib/utils'
import { getFilterMeta, filterCategory } from '../constants/nodeMetadata'
import FilterDefinitionForm from '../../filters/FilterDefinitionForm'

interface AddFilterPanelProps {
  attachedIds: Set<string>
  onAttach: (req: AttachFilterRequest) => void
}

export default function AddFilterPanel({ attachedIds, onAttach }: AddFilterPanelProps) {
  const qc = useQueryClient()
  const [open, setOpen]         = useState(false)
  const [search, setSearch]     = useState('')
  const [selected, setSel]      = useState('')
  const [phase, setPhase]       = useState<'PRE' | 'POST'>('PRE')
  const [order, setOrder]       = useState(10)
  const [showCreate, setShowCreate] = useState(false)
  const [pendingAttachId, setPendingAttachId] = useState<string | null>(null)

  const { data, refetch } = useQuery({
    queryKey: ['filters-list'],
    queryFn:  () => filtersApi.list({ size: 200 }),
    enabled:  open,
  })

  const all: FilterSummary[] = data?.content ?? []
  const filtered = search
    ? all.filter(f =>
        f.name.toLowerCase().includes(search.toLowerCase()) ||
        f.filterType.toLowerCase().includes(search.toLowerCase()),
      )
    : all

  const grouped = filtered.reduce<Record<string, FilterSummary[]>>((acc, f) => {
    const cat = filterCategory(f.filterType)
    ;(acc[cat] ??= []).push(f)
    return acc
  }, {})

  const hasResults = Object.keys(grouped).length > 0

  const doAttach = (filterId = selected) => {
    if (!filterId) return
    onAttach({ filterId, order, phase })
    setSel(''); setOpen(false); setSearch('')
  }

  // Called when the quick-create form saves successfully
  const handleCreated = async () => {
    setShowCreate(false)
    // Re-fetch filter list so the newly created filter appears
    await refetch()
    // If we came from a pending attach, find the just-created filter and attach it
    if (pendingAttachId) {
      doAttach(pendingAttachId)
      setPendingAttachId(null)
    }
    qc.invalidateQueries({ queryKey: ['filters'] })
  }

  return (
    <>
      <div className="relative">
        {/* Trigger button */}
        <button
          onClick={() => setOpen(v => !v)}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold rounded-lg shadow-lg shadow-indigo-500/25 transition-all"
        >
          <Plus className="w-3.5 h-3.5" /> Add Filter
        </button>

        {open && (
          <div className="absolute top-full mt-2 right-0 w-80 bg-[#111318] border border-white/10 rounded-xl shadow-2xl z-50 overflow-hidden">

            {/* Search */}
            <div className="p-3 border-b border-white/[0.06]">
              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-600 pointer-events-none" />
                <input
                  autoFocus
                  value={search}
                  onChange={e => setSearch(e.target.value)}
                  placeholder="Search filters…"
                  className="w-full bg-white/5 border border-white/[0.08] rounded-lg pl-8 pr-3 py-1.5 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 transition-all"
                />
              </div>
            </div>

            {/* Filter list */}
            <div className="max-h-56 overflow-y-auto">
              {!hasResults ? (
                <div className="px-3 py-5 space-y-3">
                  <p className="text-xs text-gray-600 text-center">
                    {search ? `No filters match "${search}"` : 'No filter definitions yet'}
                  </p>
                  {/* Quick-create shortcut */}
                  <button
                    onClick={() => { setOpen(false); setShowCreate(true) }}
                    className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-xs font-semibold rounded-lg transition-all"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    Create new filter
                    <ExternalLink className="w-3 h-3 opacity-60" />
                  </button>
                </div>
              ) : Object.entries(grouped).map(([cat, items]) => (
                <div key={cat}>
                  <div className="px-3 py-1 text-[9px] font-bold text-gray-600 uppercase tracking-widest bg-white/[0.02] sticky top-0">
                    {cat}
                  </div>
                  {items.map(f => {
                    const already = attachedIds.has(f.id)
                    const meta    = getFilterMeta(f.filterType)
                    return (
                      <button
                        key={f.id}
                        disabled={already}
                        onClick={() => setSel(f.id === selected ? '' : f.id)}
                        className={cn(
                          'w-full flex items-center gap-3 px-3 py-2 text-left transition-colors',
                          already           ? 'opacity-40 cursor-not-allowed' :
                          selected === f.id ? 'bg-indigo-500/15'               : 'hover:bg-white/[0.04]',
                        )}
                      >
                        <span className={cn('p-1 rounded shrink-0', meta.bg, meta.color)}>{meta.icon}</span>
                        <div className="flex-1 min-w-0">
                          <div className="text-xs font-medium text-white truncate">{f.name}</div>
                          <div className="text-[10px] text-gray-500 truncate">{f.filterType.replace(/_/g, ' ')}</div>
                        </div>
                        {selected === f.id && <span className="text-indigo-400 text-xs shrink-0">✓</span>}
                        {already           && <span className="text-[10px] text-gray-600 shrink-0">attached</span>}
                      </button>
                    )
                  })}
                </div>
              ))}
            </div>

            {/* Phase + order picker */}
            {selected && (
              <div className="p-3 border-t border-white/[0.06] space-y-2.5">
                <div className="flex gap-2">
                  {(['PRE', 'POST'] as const).map(p => (
                    <button
                      key={p}
                      onClick={() => setPhase(p)}
                      className={cn(
                        'flex-1 py-1.5 rounded-lg text-xs font-semibold border transition-all',
                        phase === p
                          ? p === 'PRE'
                            ? 'bg-blue-600/70 border-blue-500/40 text-white'
                            : 'bg-purple-600/70 border-purple-500/40 text-white'
                          : 'border-white/[0.08] text-gray-500 hover:text-gray-300',
                      )}
                    >
                      {p === 'PRE' ? '↑ PRE — before upstream' : '↓ POST — after upstream'}
                    </button>
                  ))}
                </div>

                <div className="flex items-center gap-2">
                  <label className="text-[10px] text-gray-500 shrink-0 w-10">Order</label>
                  <input
                    type="number" min={0}
                    value={order}
                    onChange={e => setOrder(Number(e.target.value))}
                    className="flex-1 bg-white/[0.04] border border-white/[0.08] rounded px-2 py-1 text-xs text-white font-mono focus:outline-none focus:border-indigo-500"
                  />
                </div>

                <button
                  onClick={() => doAttach()}
                  className="w-full py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold rounded-lg transition-colors"
                >
                  Attach to Flow
                </button>
              </div>
            )}

            <div className="px-3 py-2 border-t border-white/[0.04] flex items-center justify-between">
              <button
                onClick={() => { setOpen(false); setShowCreate(true) }}
                className="text-xs text-indigo-500 hover:text-indigo-400 transition-colors flex items-center gap-1"
              >
                <Plus className="w-3 h-3" /> New filter
              </button>
              <button
                onClick={() => { setOpen(false); setSel(''); setSearch('') }}
                className="text-xs text-gray-600 hover:text-gray-300 transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Quick-create form */}
      {showCreate && (
        <FilterDefinitionForm
          onClose={() => setShowCreate(false)}
          onSaved={handleCreated}
        />
      )}
    </>
  )
}


