/**
 * NodePalette.tsx — Left sidebar listing all draggable node types.
 *
 * Design intent:
 *  - Fixed-width dark panel that matches the AppLayout sidebar tone
 *  - Nodes are grouped by category with sticky group headers
 *  - Searchable: filter updates in real-time with no debounce needed
 *  - Single-use nodes are disabled (greyed out + "Used" badge) once placed
 *  - When the canvas is locked (route is active) the entire palette is
 *    disabled and shows a lock banner — no dragging is possible
 *  - HTML5 drag-and-drop: setData('application/reactflow', nodeType) and
 *    optionally setData('application/reactflow-filter-type', filterType)
 *    so WorkflowCanvas can instantiate / attach the correct node on drop
 */
import { useState, useCallback } from 'react'
import { Search, GripVertical, CheckCircle2, Lock } from 'lucide-react'
import { cn } from '../../../lib/utils'
import { PALETTE_NODES, CATEGORY_ORDER, type PaletteNodeDef } from '../constants/nodeMetadata'
import { useWorkflowStore } from '../store/workflowStore'

// Group the catalog by category for section rendering
function groupByCategory(nodes: PaletteNodeDef[]): Record<string, PaletteNodeDef[]> {
  return nodes.reduce<Record<string, PaletteNodeDef[]>>((acc, n) => {
    ;(acc[n.category] ??= []).push(n)
    return acc
  }, {})
}

interface NodePaletteProps {
  /** Optional extra class names on the root element */
  className?: string
}

export default function NodePalette({ className }: NodePaletteProps) {
  const [search, setSearch] = useState('')
  const { usedNodeTypes, filterCount, routeStatus } = useWorkflowStore()

  /** Palette is fully locked when the route is live */
  const isLocked = routeStatus === 'ACTIVE'

  // Filter palette items by label or category
  const filtered = search
    ? PALETTE_NODES.filter(
        (n) =>
          n.label.toLowerCase().includes(search.toLowerCase()) ||
          n.category.toLowerCase().includes(search.toLowerCase()) ||
          n.description.toLowerCase().includes(search.toLowerCase()) ||
          (n.filterType ?? '').toLowerCase().includes(search.toLowerCase()),
      )
    : PALETTE_NODES

  const grouped = groupByCategory(filtered)

  // Wire HTML5 drag event so WorkflowCanvas's onDrop can read node type + filter type
  const onDragStart = useCallback(
    (event: React.DragEvent, node: PaletteNodeDef) => {
      // Locked: block all drags
      if (isLocked) {
        event.preventDefault()
        return
      }
      // A singleUse structural node that's already on the canvas cannot be dragged
      if (node.singleUse && usedNodeTypes.has(node.type)) return
      // A filter type already in use on the canvas cannot be dragged again
      if (node.filterType && usedNodeTypes.has(`filter:${node.filterType}`)) return
      event.dataTransfer.setData('application/reactflow', node.type)
      if (node.filterType) {
        event.dataTransfer.setData('application/reactflow-filter-type', node.filterType)
      }
      event.dataTransfer.effectAllowed = 'move'
    },
    [usedNodeTypes, isLocked],
  )

  const filterWarning = filterCount >= 3

  return (
    <div
      className={cn(
        'flex flex-col w-56 shrink-0 bg-[#0d0f14] border-r border-white/[0.06] h-full overflow-hidden',
        isLocked && 'pointer-events-none',
        className,
      )}
    >
      {/* ── Lock banner ───────────────────────────────────────────────────── */}
      {isLocked && (
        <div className="flex items-center gap-2 px-3 py-2 bg-emerald-500/[0.08] border-b border-emerald-500/20 shrink-0">
          <Lock className="w-3 h-3 text-emerald-400 shrink-0" />
          <p className="text-[10px] text-emerald-400 leading-tight">Pause the route to add nodes</p>
        </div>
      )}

      {/* ── Header ────────────────────────────────────────────────────────── */}
      <div className="px-4 pt-5 pb-3 shrink-0">
        <div className="text-[11px] font-bold text-gray-400 uppercase tracking-widest mb-3">Node Palette</div>

        {/* Search box */}
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-600 pointer-events-none" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search nodes…"
            disabled={isLocked}
            className={cn(
              'w-full bg-white/[0.04] border border-white/[0.07] rounded-lg pl-8 pr-3 py-1.5 text-xs text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 transition-all',
              isLocked && 'opacity-50 cursor-not-allowed',
            )}
          />
        </div>

        {/* Filter count warning */}
        {filterWarning && !isLocked && (
          <div className="mt-2.5 flex items-start gap-1.5 p-2 rounded-lg bg-amber-500/10 border border-amber-500/20 text-[10px] text-amber-400 leading-relaxed">
            <span className="shrink-0 mt-px">⚠️</span>
            <span>Using more than 3 filters may introduce high latency</span>
          </div>
        )}
      </div>

      {/* ── Node list ─────────────────────────────────────────────────────── */}
      <div className={cn('flex-1 overflow-y-auto px-2 pb-4 space-y-1', isLocked && 'opacity-50')}>
        {filtered.length === 0 ? (
          <p className="text-xs text-gray-600 text-center py-8">No matches</p>
        ) : (
          CATEGORY_ORDER.filter((cat) => grouped[cat]?.length).map((cat) => (
            <div key={cat}>
              {/* Sticky category header */}
              <div className="sticky top-0 bg-[#0d0f14] px-2 py-1.5 z-10">
                <span className="text-[9px] font-bold text-gray-600 uppercase tracking-widest">{cat}</span>
              </div>

              {grouped[cat].map((node) => {
                const isUsed =
                  !isLocked &&
                  !!(
                    (node.singleUse && usedNodeTypes.has(node.type)) ||
                    (node.filterType && usedNodeTypes.has(`filter:${node.filterType}`))
                  )
                return (
                  <DraggableNodeCard
                    key={`${node.type}-${node.filterType ?? ''}`}
                    node={node}
                    isUsed={isUsed}
                    isLocked={isLocked}
                    onDragStart={onDragStart}
                  />
                )
              })}
            </div>
          ))
        )}
      </div>

      {/* ── Footer hint ───────────────────────────────────────────────────── */}
      <div className="px-4 py-3 border-t border-white/[0.04] shrink-0">
        <p className="text-[10px] text-gray-700 leading-relaxed">
          {isLocked
            ? 'Canvas is read-only while the route is active.'
            : 'Drag a node onto the canvas to add it to the flow. Core nodes (grey badge) can only be used once.'}
        </p>
      </div>
    </div>
  )
}

// ─── Individual draggable card ────────────────────────────────────────────────

function DraggableNodeCard({
  node,
  isUsed,
  isLocked,
  onDragStart,
}: {
  node: PaletteNodeDef
  isUsed: boolean
  isLocked: boolean
  onDragStart: (event: React.DragEvent, node: PaletteNodeDef) => void
}) {
  const disabled = isUsed || isLocked
  return (
    <div
      draggable={!disabled}
      onDragStart={disabled ? undefined : (e) => onDragStart(e, node)}
      className={cn(
        'flex items-center gap-2.5 px-2.5 py-2 rounded-lg',
        'border border-transparent transition-all duration-100 select-none group',
        disabled
          ? 'opacity-40 cursor-not-allowed'
          : 'cursor-grab active:cursor-grabbing hover:border-white/[0.08] hover:bg-white/[0.03]',
      )}
      title={
        isLocked
          ? 'Pause the route to add this node'
          : isUsed
            ? `${node.label} is already on the canvas`
            : node.description
      }
    >
      {/* Drag grip indicator */}
      <GripVertical
        className={cn(
          'w-3 h-3 shrink-0 transition-colors',
          disabled ? 'text-gray-700' : 'text-gray-700 group-hover:text-gray-500',
        )}
      />

      {/* Accent icon */}
      <div className={cn('p-1.5 rounded-md shrink-0', node.bg, 'border', node.border)}>
        <span className={node.color}>{node.icon}</span>
      </div>

      {/* Label + description */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1">
          <div
            className={cn(
              'text-[11px] font-semibold transition-colors truncate',
              disabled ? 'text-gray-600' : 'text-gray-300 group-hover:text-white',
            )}
          >
            {node.label}
          </div>
          {isUsed && !isLocked && <CheckCircle2 className="w-3 h-3 text-emerald-500 shrink-0" />}
          {isLocked && <Lock className="w-2.5 h-2.5 text-gray-700 shrink-0" />}
        </div>
        <div className="text-[9px] text-gray-600 truncate">{node.description}</div>
      </div>
    </div>
  )
}
