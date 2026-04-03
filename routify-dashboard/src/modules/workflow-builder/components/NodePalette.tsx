/**
 * NodePalette.tsx — Left sidebar listing all draggable node types.
 *
 * Design intent:
 *  - Fixed-width dark panel that matches the AppLayout sidebar tone
 *  - Nodes are grouped by category with sticky group headers
 *  - Searchable: filter updates in real-time with no debounce needed
 *  - HTML5 drag-and-drop: setData('application/reactflow', nodeType)
 *    so WorkflowCanvas can instantiate the correct node on drop
 */
import { useState, useCallback } from 'react'
import { Search, GripVertical } from 'lucide-react'
import { cn } from '../../../lib/utils'
import { PALETTE_NODES, type PaletteNodeDef } from '../constants/nodeMetadata'

// Group the catalog by category for section rendering
function groupByCategory(nodes: PaletteNodeDef[]): Record<string, PaletteNodeDef[]> {
  return nodes.reduce<Record<string, PaletteNodeDef[]>>((acc, n) => {
    (acc[n.category] ??= []).push(n)
    return acc
  }, {})
}

// Category order for stable rendering
const CATEGORY_ORDER = ['Core', 'Auth', 'Rate Limiting', 'Resilience', 'Transformation', 'Routing']

interface NodePaletteProps {
  /** Optional extra class names on the root element */
  className?: string
}

export default function NodePalette({ className }: NodePaletteProps) {
  const [search, setSearch] = useState('')

  // Filter palette items by label or category
  const filtered = search
    ? PALETTE_NODES.filter(n =>
        n.label.toLowerCase().includes(search.toLowerCase()) ||
        n.category.toLowerCase().includes(search.toLowerCase()) ||
        n.description.toLowerCase().includes(search.toLowerCase()),
      )
    : PALETTE_NODES

  const grouped = groupByCategory(filtered)

  // Wire HTML5 drag event so React Flow's onDrop can read the node type
  const onDragStart = useCallback((event: React.DragEvent, nodeType: string) => {
    event.dataTransfer.setData('application/reactflow', nodeType)
    event.dataTransfer.effectAllowed = 'move'
  }, [])

  return (
    <div
      className={cn(
        'flex flex-col w-56 shrink-0 bg-[#0d0f14] border-r border-white/[0.06] h-full overflow-hidden',
        className,
      )}
    >
      {/* ── Header ────────────────────────────────────────────────────────── */}
      <div className="px-4 pt-5 pb-3 shrink-0">
        <div className="text-[11px] font-bold text-gray-400 uppercase tracking-widest mb-3">
          Node Palette
        </div>

        {/* Search box */}
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-600 pointer-events-none" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search nodes…"
            className="w-full bg-white/[0.04] border border-white/[0.07] rounded-lg pl-8 pr-3 py-1.5 text-xs text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 transition-all"
          />
        </div>
      </div>

      {/* ── Node list ─────────────────────────────────────────────────────── */}
      <div className="flex-1 overflow-y-auto px-2 pb-4 space-y-1">
        {filtered.length === 0 ? (
          <p className="text-xs text-gray-600 text-center py-8">No matches</p>
        ) : (
          CATEGORY_ORDER.filter(cat => grouped[cat]?.length).map(cat => (
            <div key={cat}>
              {/* Sticky category header */}
              <div className="sticky top-0 bg-[#0d0f14] px-2 py-1.5 z-10">
                <span className="text-[9px] font-bold text-gray-600 uppercase tracking-widest">{cat}</span>
              </div>

              {grouped[cat].map(node => (
                <DraggableNodeCard
                  key={node.type}
                  node={node}
                  onDragStart={onDragStart}
                />
              ))}
            </div>
          ))
        )}
      </div>

      {/* ── Footer hint ───────────────────────────────────────────────────── */}
      <div className="px-4 py-3 border-t border-white/[0.04] shrink-0">
        <p className="text-[10px] text-gray-700 leading-relaxed">
          Drag a node onto the canvas to add it to the flow.
        </p>
      </div>
    </div>
  )
}

// ─── Individual draggable card ────────────────────────────────────────────────

function DraggableNodeCard({
  node,
  onDragStart,
}: {
  node: PaletteNodeDef
  onDragStart: (event: React.DragEvent, nodeType: string) => void
}) {
  return (
    <div
      draggable
      onDragStart={e => onDragStart(e, node.type)}
      className={cn(
        'flex items-center gap-2.5 px-2.5 py-2 rounded-lg cursor-grab active:cursor-grabbing',
        'border border-transparent hover:border-white/[0.08] hover:bg-white/[0.03]',
        'transition-all duration-100 select-none group',
      )}
      title={node.description}
    >
      {/* Drag grip indicator */}
      <GripVertical className="w-3 h-3 text-gray-700 group-hover:text-gray-500 shrink-0 transition-colors" />

      {/* Accent icon */}
      <div className={cn('p-1.5 rounded-md shrink-0', node.bg, 'border', node.border)}>
        <span className={node.color}>{node.icon}</span>
      </div>

      {/* Label + description */}
      <div className="flex-1 min-w-0">
        <div className="text-[11px] font-semibold text-gray-300 group-hover:text-white transition-colors truncate">
          {node.label}
        </div>
        <div className="text-[9px] text-gray-600 truncate">{node.description}</div>
      </div>
    </div>
  )
}

