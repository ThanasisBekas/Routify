import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { filtersApi } from '../../api/filtersApi'
import FilterDefinitionForm from './FilterDefinitionForm'
import type { FilterSummary } from '../../types'
import { Filter, Plus, Pencil, Trash2, Loader2, Link } from 'lucide-react'
import { cn } from '../../lib/utils'

const FILTER_TYPE_COLORS: Record<string, string> = {
  AUTH_JWT:                'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
  AUTH_API_KEY:            'text-blue-400 bg-blue-400/10 border-blue-400/20',
  RATE_LIMIT_TOKEN_BUCKET: 'text-amber-400 bg-amber-400/10 border-amber-400/20',
  CIRCUIT_BREAKER:         'text-orange-400 bg-orange-400/10 border-orange-400/20',
  BODY_JOLT_TRANSFORM:     'text-purple-400 bg-purple-400/10 border-purple-400/20',
}

export default function FilterDefinitionList() {
  const qc = useQueryClient()
  const [showForm,   setShowForm]   = useState(false)
  const [editingId,  setEditingId]  = useState<string | undefined>()

  const { data, isLoading } = useQuery({
    queryKey: ['filters'],
    queryFn: () => filtersApi.list({ page: 0, size: 50 }),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => filtersApi.delete(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['filters'] }),
  })

  const filters = data?.content ?? []

  return (
    <div className="flex flex-col h-full animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-5 border-b border-white/[0.06] bg-[#0c0e14]">
        <div>
          <h1 className="text-lg font-bold text-white tracking-tight mb-1">Filters</h1>
          <p className="text-sm text-gray-500">
            {filters.length} reusable filter{filters.length !== 1 ? 's' : ''} · attach to any route
          </p>
        </div>
        <button
          onClick={() => { setEditingId(undefined); setShowForm(true) }}
          className="flex items-center gap-2 px-3.5 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold rounded-lg transition-all shadow-lg shadow-indigo-500/20"
        >
          <Plus className="w-4 h-4" />
          New Filter
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center py-24 gap-3">
            <div className="w-7 h-7 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
            <p className="text-sm text-gray-500">Loading filters…</p>
          </div>
        ) : filters.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-24 gap-4">
            <div className="w-16 h-16 rounded-2xl bg-white/[0.03] border border-white/[0.06] flex items-center justify-center">
              <Filter className="w-7 h-7 text-gray-600" />
            </div>
            <div className="text-center">
              <p className="text-sm font-medium text-gray-300 mb-1">No filter definitions yet</p>
              <p className="text-xs text-gray-600">Create reusable filters and attach them to any route</p>
            </div>
            <button
              onClick={() => setShowForm(true)}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-sm font-medium rounded-lg transition-all"
            >
              <Plus className="w-4 h-4" />
              Create first filter
            </button>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b border-white/[0.06] bg-[#0c0e14]">
                <th className="px-6 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Filter</th>
                <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Type</th>
                <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Gateway Link</th>
                <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Usage</th>
                <th className="px-4 py-3 text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Created</th>
                <th className="px-4 py-3 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filters.map((f) => (
                <FilterRow
                  key={f.id}
                  filter={f}
                  onEdit={() => { setEditingId(f.id); setShowForm(true) }}
                  onDelete={() => {
                    if (window.confirm(`Delete filter "${f.name}"?`)) deleteMutation.mutate(f.id)
                  }}
                  isDeleting={deleteMutation.isPending && deleteMutation.variables === f.id}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {showForm && (
        <FilterDefinitionForm
          editingId={editingId}
          onClose={() => { setShowForm(false); setEditingId(undefined) }}
          onSaved={() => {
            setShowForm(false)
            setEditingId(undefined)
            qc.invalidateQueries({ queryKey: ['filters'] })
          }}
        />
      )}
    </div>
  )
}

function FilterRow({
  filter, onEdit, onDelete, isDeleting
}: {
  filter: FilterSummary
  onEdit: () => void
  onDelete: () => void
  isDeleting: boolean
}) {
  const colorClass = FILTER_TYPE_COLORS[filter.filterType] ?? 'text-gray-400 bg-gray-400/10 border-gray-400/20'

  return (
    <tr className="border-b border-white/[0.04] hover:bg-white/[0.025] group transition-colors">
      <td className="px-6 py-3.5">
        <span className="font-semibold text-white text-sm">{filter.name}</span>
      </td>
      <td className="px-4 py-3.5">
        <span className={cn('inline-flex text-[11px] px-2 py-1 rounded-full font-semibold border', colorClass)}>
          {filter.filterType.replace(/_/g, ' ')}
        </span>
      </td>
      <td className="px-4 py-3.5">
        {filter.gatewayConfigRef ? (
          <span
            title={`${filter.gatewayConfigRef.refType}: ${filter.gatewayConfigRef.refName ?? filter.gatewayConfigRef.refId}`}
            className="inline-flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-full border text-indigo-400 bg-indigo-400/10 border-indigo-400/20"
          >
            <Link className="w-3 h-3 shrink-0" />
            <span className="max-w-[120px] truncate">
              {filter.gatewayConfigRef.refName ?? filter.gatewayConfigRef.refId}
            </span>
          </span>
        ) : (
          <span className="text-xs text-gray-600">—</span>
        )}
      </td>
      <td className="px-4 py-3.5">
        <span className={cn(
          'inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full border',
          filter.usageCount > 0
            ? 'text-indigo-400 bg-indigo-400/10 border-indigo-400/20'
            : 'text-gray-500 bg-white/[0.03] border-white/[0.06]'
        )}>
          {filter.usageCount} route{filter.usageCount !== 1 ? 's' : ''}
        </span>
      </td>
      <td className="px-4 py-3.5 text-xs text-gray-500">
        {new Date(filter.createdAt).toLocaleDateString()}
      </td>
      <td className="px-4 py-3.5">
        <div className="flex items-center justify-end gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
          <button
            onClick={onEdit}
            className="p-1.5 rounded-md text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors"
          >
            <Pencil className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={onDelete}
            disabled={isDeleting || filter.usageCount > 0}
            title={filter.usageCount > 0 ? 'Detach from all routes first' : 'Delete'}
            className="p-1.5 rounded-md text-red-400 hover:bg-red-400/10 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
          >
            {isDeleting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
          </button>
        </div>
      </td>
    </tr>
  )
}
