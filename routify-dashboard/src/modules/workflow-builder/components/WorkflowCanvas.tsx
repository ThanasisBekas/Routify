/**
 * WorkflowCanvas.tsx — The core React Flow canvas wrapper for the WorkflowBuilder.
 *
 * Responsibilities:
 *  - Consumes workflowStore for nodes / edges / selection
 *  - Accepts HTML5 drops from NodePalette:
 *      • Core nodes (clientNode, routeNode, upstreamNode, responseNode)
 *        are single-use — enforced by checking usedNodeTypes before adding
 *      • Filter nodes open an inline FilterPickerModal so the user selects
 *        a filter definition and attaches it; phase is inferred automatically
 *        from drop position relative to the upstream node
 *  - Wires onConnect to automatically colour-code new edges
 *  - Handles node/edge deletion — keyboard Delete key and UI button
 *  - Restores filter types to NodePalette when a filter node is deleted
 *  - Renders Background, Controls, MiniMap, ValidationBanner
 *  - Exposes PropertiesDrawer as an overlay (absolutely positioned)
 *
 * Phase & order are ALWAYS derived from graph topology — never manually set by the user.
 */
import { useCallback, useMemo, useRef, useState } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Panel,
  addEdge,
  applyNodeChanges,
  applyEdgeChanges,
  type Connection,
  type Node as FlowNode,
  type NodeChange,
  type EdgeChange,
  type ReactFlowInstance,
  BackgroundVariant,
  type XYPosition,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useQueryClient, useMutation, useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Search, X, Plus, RefreshCw, Filter, Trash2 } from 'lucide-react'
import { routesApi } from '../../../api/routesApi'
import { filtersApi } from '../../../api/filtersApi'
import { useWorkflowStore, SINGLE_USE_NODE_TYPES } from '../store/workflowStore'
import { edgeStyle, getFilterMeta } from '../constants/nodeMetadata'
import { isFlowComplete, inferExecutionOrder } from '../hooks/buildGraph'
import { RouteTriggerNode } from '../nodes/RouteTriggerNode'
import {
  ClientNode, UpstreamNode, FilterNode, ResponseNode, LabelNode,
} from '../nodes/SharedNodes'
import ValidationBanner from './ValidationBanner'
import PropertiesDrawer from './PropertiesDrawer'
import type { RouteDto, AttachFilterRequest, FilterSummary } from '../../../types'
import { cn } from '../../../lib/utils'
import { extractApiError } from '../../../lib/utils'

// ─── Auto-spacing: collision avoidance for dropped nodes ──────────────────────

/** Approximate bounding box dimensions for canvas nodes (generous to avoid visual overlap) */
const NODE_WIDTH  = 240
const NODE_HEIGHT = 120
const SPACING     = 20

/**
 * Returns a position that does not overlap any existing node on the canvas.
 * If the proposed position is free it is returned as-is. Otherwise the
 * algorithm scans downward (and then rightward) in increments until an
 * open slot is found.  Maximum 50 attempts to prevent infinite loops.
 */
function findNonOverlappingPosition(
  proposed: XYPosition,
  existingNodes: FlowNode[],
): XYPosition {
  const overlaps = (pos: XYPosition) =>
    existingNodes.some(n => (
      pos.x < n.position.x + NODE_WIDTH  + SPACING &&
      pos.x + NODE_WIDTH  + SPACING > n.position.x &&
      pos.y < n.position.y + NODE_HEIGHT + SPACING &&
      pos.y + NODE_HEIGHT + SPACING > n.position.y
    ))

  if (!overlaps(proposed)) return proposed

  // Scan downward first, then shift right
  for (let attempt = 1; attempt <= 50; attempt++) {
    const below: XYPosition = {
      x: proposed.x,
      y: proposed.y + attempt * (NODE_HEIGHT + SPACING),
    }
    if (!overlaps(below)) return below

    const right: XYPosition = {
      x: proposed.x + attempt * (NODE_WIDTH + SPACING),
      y: proposed.y,
    }
    if (!overlaps(right)) return right
  }

  // Fallback — shouldn't happen in practice
  return { x: proposed.x, y: proposed.y + 200 }
}

// ─── Node type registry ───────────────────────────────────────────────────────
// Defined outside the component to prevent React Flow from re-registering on
// every render (causes internal reconciliation warnings in @xyflow/react).

const NODE_TYPES = {
  clientNode:    ClientNode,
  routeNode:     RouteTriggerNode,
  filterNode:    FilterNode,
  upstreamNode:  UpstreamNode,
  responseNode:  ResponseNode,
  labelNode:     LabelNode,
}

// ─── FilterPickerModal ────────────────────────────────────────────────────────
// Phase and order are NOT shown as editable inputs — they are derived automatically
// from the graph topology after the node is placed on the canvas.

interface FilterPickerModalProps {
  filterType: string
  inferredPhase: 'PRE' | 'POST'
  inferredOrder: number
  onAttach: (req: AttachFilterRequest) => void
  onClose: () => void
  isPending: boolean
}

function FilterPickerModal({
  filterType, inferredPhase, inferredOrder, onAttach, onClose, isPending
}: FilterPickerModalProps) {
  const [search, setSearch] = useState('')
  const [selectedId, setSelectedId] = useState('')

  const { data } = useQuery({
    queryKey: ['filters-list'],
    queryFn: () => filtersApi.list({ size: 100 }),
  })
  const all: FilterSummary[] = data?.content ?? []

  const filtered = (search
    ? all.filter(f =>
        f.name.toLowerCase().includes(search.toLowerCase()) ||
        f.filterType.toLowerCase().includes(search.toLowerCase()),
      )
    : all
  ).filter(f => !filterType || f.filterType === filterType)

  const meta = getFilterMeta(filterType)
  const humanType = filterType.replace(/_/g, ' ')

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-[60] p-4 animate-fade-in">
      <div className="bg-[#111318] border border-white/[0.08] rounded-2xl w-full max-w-md shadow-2xl flex flex-col max-h-[80vh] animate-fade-in-up">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/[0.06] shrink-0">
          <div className="flex items-center gap-2.5">
            <div className={cn('p-2 rounded-lg border', meta.bg, meta.border)}>
              <span className={meta.color}>{meta.icon}</span>
            </div>
            <div>
              <div className="text-[10px] text-gray-500 uppercase tracking-widest font-medium">Attach Filter</div>
              <div className="text-sm font-bold text-white">{humanType}</div>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/5 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Filter definition picker */}
        <div className="flex-1 overflow-y-auto">
          <div className="p-4 space-y-3">
            <div className="relative">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-600 pointer-events-none" />
              <input
                autoFocus
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder={`Search ${humanType} definitions…`}
                className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg pl-8 pr-3 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 transition-all"
              />
            </div>

            {filtered.length === 0 ? (
              <div className="text-center py-8 space-y-2">
                <Filter className="w-7 h-7 text-gray-700 mx-auto" />
                <p className="text-xs text-gray-500">
                  No {humanType} filter definitions found.
                </p>
                <p className="text-[11px] text-gray-600">
                  Create one in the <strong className="text-gray-400">Filters</strong> section first.
                </p>
              </div>
            ) : (
              <div className="space-y-1">
                {filtered.map(f => (
                  <button
                    key={f.id}
                    onClick={() => setSelectedId(f.id === selectedId ? '' : f.id)}
                    className={cn(
                      'w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-left transition-colors border',
                      selectedId === f.id
                        ? 'bg-indigo-500/15 border-indigo-500/30'
                        : 'bg-white/[0.02] border-white/[0.06] hover:bg-white/[0.04]',
                    )}
                  >
                    <span className={cn('p-1.5 rounded-lg', meta.bg)}>{meta.icon}</span>
                    <div className="flex-1 min-w-0">
                      <div className={cn('text-sm font-medium truncate', selectedId === f.id ? 'text-indigo-200' : 'text-white')}>
                        {f.name}
                      </div>
                      <div className="text-[10px] text-gray-500">
                        {f.usageCount > 0 ? `Used on ${f.usageCount} route${f.usageCount !== 1 ? 's' : ''}` : 'Not attached anywhere yet'}
                      </div>
                    </div>
                    {selectedId === f.id && <span className="text-indigo-400 text-sm shrink-0">✓</span>}
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Phase & order info — read-only, auto-inferred */}
          {selectedId && (
            <div className="px-4 pb-4 space-y-3 border-t border-white/[0.06] pt-4">
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <label className="text-[10px] font-semibold text-gray-500 uppercase tracking-widest block">Phase</label>
                  <div className={cn(
                    'flex items-center justify-center py-1.5 rounded-lg border text-xs font-semibold',
                    inferredPhase === 'PRE'
                      ? 'bg-blue-600/20 border-blue-500/30 text-blue-200'
                      : 'bg-purple-600/20 border-purple-500/30 text-purple-200',
                  )}>
                    {inferredPhase === 'PRE' ? '↑ PRE' : '↓ POST'}
                  </div>
                  <p className="text-[9px] text-gray-600">
                    Auto-inferred from drop position
                  </p>
                </div>
                <div className="space-y-1.5">
                  <label className="text-[10px] font-semibold text-gray-500 uppercase tracking-widest block">Order</label>
                  <div className="w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-1.5 text-sm text-gray-300 font-mono text-center">
                    {inferredOrder}
                  </div>
                  <p className="text-[9px] text-gray-600">Auto-derived from canvas</p>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3.5 border-t border-white/[0.06] shrink-0">
          <button
            onClick={onClose}
            className="px-3 py-1.5 text-sm text-gray-400 hover:text-white transition-colors"
          >
            Cancel
          </button>
          <button
            disabled={!selectedId || isPending}
            onClick={() => onAttach({ filterId: selectedId, order: inferredOrder, phase: inferredPhase })}
            className="flex items-center gap-1.5 px-4 py-1.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-semibold rounded-lg transition-colors"
          >
            {isPending ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
            Attach to Flow
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Component ────────────────────────────────────────────────────────────────

interface WorkflowCanvasProps {
  route: RouteDto
}

interface PendingFilterDrop {
  filterType: string
  position: XYPosition
  inferredPhase: 'PRE' | 'POST'
  inferredOrder: number
}

export default function WorkflowCanvas({ route }: WorkflowCanvasProps) {
  const qc = useQueryClient()
  const rfInstance = useRef<ReactFlowInstance | null>(null)
  const [pendingDrop, setPendingDrop] = useState<PendingFilterDrop | null>(null)

  /** Canvas is locked when the route is live — no structural mutations allowed */
  const isLocked = route.status === 'ACTIVE'

  const {
    nodes, edges,
    setNodes, setEdges,
    selectNode,
    selectedNodeId,
    usedNodeTypes,
  } = useWorkflowStore()

  // ── Mutations ──────────────────────────────────────────────────────────────

  const detachMutation = useMutation({
    mutationFn: (filterId: string) => routesApi.detachFilter(route.id, filterId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['route', route.id] }),
  })

  const attachMutation = useMutation({
    mutationFn: (req: AttachFilterRequest) => routesApi.attachFilter(route.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['route', route.id] })
      setPendingDrop(null)
      toast.success('Filter attached', { description: 'Canvas will refresh from backend.' })
    },
    onError: (err) => toast.error('Attach failed', { description: extractApiError(err) }),
  })

  const onDetach = useCallback((id: string) => detachMutation.mutate(id), [detachMutation])
  const onSelect = useCallback((nodeId: string) => selectNode(nodeId), [selectNode])

  // ── React Flow event handlers ──────────────────────────────────────────────

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => {
      if (isLocked) {
        console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId: route.id, action: 'onNodesChange' })
        return
      }
      setNodes(applyNodeChanges(changes, nodes), 'onNodesChange')
    },
    [nodes, setNodes, isLocked, route.id],
  )

  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      if (isLocked) {
        console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId: route.id, action: 'onEdgesChange' })
        return
      }
      setEdges(applyEdgeChanges(changes, edges), 'onEdgesChange')
    },
    [edges, setEdges, isLocked, route.id],
  )

  const onConnect = useCallback(
    (params: Connection) => {
      if (isLocked) {
        console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId: route.id, action: 'onConnect' })
        return
      }
      // Derive edge colour from the source node's position in the pipeline
      const src = nodes.find(n => n.id === params.source)
      const type =
        src?.id === 'route'                                              ? 'route' :
        src?.id?.startsWith('post-') || params.target === 'response'   ? 'post'  : 'pre'
      const newEdges = addEdge({ ...params, ...edgeStyle(type) }, edges)
      setEdges(newEdges, 'onConnect')
    },
    [nodes, edges, setEdges, isLocked, route.id],
  )

  // ── Delete selected node ──────────────────────────────────────────────────
  const deleteSelectedNode = useCallback(() => {
    if (isLocked) {
      console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId: route.id, action: 'deleteSelectedNode' })
      toast.warning('Route is active', { description: 'Pause the route first to remove nodes.' })
      return
    }
    if (!selectedNodeId) return
    const node = nodes.find(n => n.id === selectedNodeId)
    if (!node) return

    // Prevent deletion of core structural nodes
    if (SINGLE_USE_NODE_TYPES.has(node.type ?? '')) {
      toast.warning('Cannot remove core nodes', {
        description: 'Client, Route, Upstream, and Response are required structural nodes.',
      })
      return
    }

    // If it's a filter node that is attached in backend, detach it first
    if (node.type === 'filterNode') {
      const filterId = (node.data as { filter: { filterId: string } })?.filter?.filterId
      if (filterId) {
        detachMutation.mutate(filterId)
        return
      }
    }

    // For unattached/local nodes: remove from canvas
    const newNodes = nodes.filter(n => n.id !== selectedNodeId)
    const newEdges = edges.filter(e => e.source !== selectedNodeId && e.target !== selectedNodeId)
    setNodes(newNodes, 'deleteNode')
    setEdges(newEdges, 'deleteNode')
    selectNode(null)
  }, [selectedNodeId, nodes, edges, setNodes, setEdges, selectNode, detachMutation, isLocked, route.id])

  // ── Drag-and-drop from NodePalette ────────────────────────────────────────

  const onDragOver = useCallback((event: React.DragEvent) => {
    if (isLocked) {
      event.dataTransfer.dropEffect = 'none'
      return
    }
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }, [isLocked])

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault()
      if (isLocked) {
        console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId: route.id, action: 'onDrop' })
        toast.warning('Route is active', { description: 'Pause the route first to add nodes.' })
        return
      }

      const nodeType   = event.dataTransfer.getData('application/reactflow')
      const filterType = event.dataTransfer.getData('application/reactflow-filter-type')

      if (!nodeType || !rfInstance.current) return

      const position = rfInstance.current.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      })

      // ── Single-use structural node enforcement ──────────────────────────────
      if (SINGLE_USE_NODE_TYPES.has(nodeType)) {
        if (usedNodeTypes.has(nodeType)) {
          toast.warning(`Only one ${nodeType.replace('Node', '')} node allowed`, {
            description: 'This node type is already on the canvas.',
          })
          return
        }
        const safePosition = findNonOverlappingPosition(position, nodes)
        const newNode = {
          id: `${nodeType}-${Date.now()}`,
          type: nodeType,
          position: safePosition,
          data: defaultDataForType(nodeType, onDetach, onSelect),
        }
        setNodes([...nodes, newNode], 'dropStructural')
        return
      }

      // ── Filter node drop → open picker ─────────────────────────────────────
      if (nodeType === 'filterNode' && filterType) {
        if (usedNodeTypes.has(`filter:${filterType}`)) {
          toast.warning(`${filterType.replace(/_/g, ' ')} already on canvas`, {
            description: 'Each filter type can only be used once per route.',
          })
          return
        }

        const upstreamNode = nodes.find(n => n.id === 'upstream')
        const upstreamX = upstreamNode?.position?.x ?? 600
        const existingExec = inferExecutionOrder(nodes, edges)
        const preCount  = existingExec.filter(e => e.phase === 'PRE').length
        const postCount = existingExec.filter(e => e.phase === 'POST').length
        const inferredPhase: 'PRE' | 'POST' = position.x < upstreamX ? 'PRE' : 'POST'
        const inferredOrder = inferredPhase === 'PRE' ? preCount * 10 : postCount * 10
        const safeFilterPosition = findNonOverlappingPosition(position, nodes)

        setPendingDrop({ filterType, position: safeFilterPosition, inferredPhase, inferredOrder })
        return
      }

      // Fallback
      const safeFallbackPosition = findNonOverlappingPosition(position, nodes)
      const newNode = {
        id: `${nodeType}-${Date.now()}`,
        type: nodeType,
        position: safeFallbackPosition,
        data: defaultDataForType(nodeType, onDetach, onSelect),
      }
      setNodes([...nodes, newNode], 'dropFallback')
    },
    [nodes, edges, setNodes, onDetach, onSelect, usedNodeTypes, setPendingDrop, isLocked, route.id],
  )

  // ── Node click → open PropertiesDrawer ────────────────────────────────────

  const onNodeClick = useCallback(
    (_event: React.MouseEvent, node: { id: string }) => {
      selectNode(node.id)
    },
    [selectNode],
  )

  // ── Canvas init → fitView ─────────────────────────────────────────────────

  const onInit = useCallback((instance: ReactFlowInstance) => {
    rfInstance.current = instance
    setTimeout(() => instance.fitView({ padding: 0.14, duration: 400 }), 80)
  }, [])

  // ── Validation ────────────────────────────────────────────────────────────

  const valid = useMemo(() => isFlowComplete(nodes, edges), [nodes, edges])

  // Delete button only shown when not locked and node is deletable
  const selectedNode = nodes.find(n => n.id === selectedNodeId)
  const canDeleteSelected = !isLocked && !!selectedNode && !SINGLE_USE_NODE_TYPES.has(selectedNode.type ?? '')

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className={cn('relative flex-1 h-full bg-[#080a0f]', isLocked && 'opacity-75 cursor-not-allowed select-none')}>
      {/* Lock overlay — intercepts all pointer events when active */}
      {isLocked && (
        <div
          className="absolute inset-0 z-10 cursor-not-allowed"
          title="Route is active — pause it to make changes"
          onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'none' }}
          onDrop={(e) => e.preventDefault()}
        />
      )}
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={isLocked ? undefined : onConnect}
        onNodeClick={onNodeClick}
        onDrop={onDrop}
        onDragOver={onDragOver}
        onInit={onInit}
        fitView
        fitViewOptions={{ padding: 0.14 }}
        minZoom={0.1}
        maxZoom={2}
        deleteKeyCode={isLocked ? null : ['Delete', 'Backspace']}
        nodesConnectable={!isLocked}
        nodesDraggable={!isLocked}
        proOptions={{ hideAttribution: true }}
        className="bg-[#080a0f]"
        connectionLineStyle={{ stroke: '#6366f1', strokeWidth: 2, strokeDasharray: '6 3' }}
        defaultEdgeOptions={{ type: 'smoothstep' }}
      >
        {/* Dot-grid background */}
        <Background
          variant={BackgroundVariant.Dots}
          gap={24}
          size={1}
          color="rgba(255,255,255,0.04)"
        />

        {/* Zoom / pan controls */}
        <Controls
          className="!bg-[#111318] !border-white/10 !rounded-xl !shadow-xl"
          showInteractive={false}
        />

        {/* Overview minimap */}
        <MiniMap
          className="!bg-[#0d0f14] !border-white/10 !rounded-xl"
          nodeColor="#1e2030"
          maskColor="rgba(0,0,0,0.4)"
        />

        {/* Validation banner — top-right */}
        <Panel position="top-right" className="flex items-center gap-2 pr-2 pt-2">
          <ValidationBanner valid={valid} />
        </Panel>

        {/* Delete selected node button — top-left (hidden when locked) */}
        {canDeleteSelected && (
          <Panel position="top-left" className="pl-2 pt-2">
            <button
              onClick={deleteSelectedNode}
              disabled={detachMutation.isPending}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-red-400 bg-red-400/10 border border-red-400/20 hover:bg-red-400/20 disabled:opacity-50 transition-all shadow"
              title="Delete selected node (or press Delete)"
            >
              {detachMutation.isPending
                ? <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                : <Trash2 className="w-3.5 h-3.5" />
              }
              Remove Node
            </button>
          </Panel>
        )}

        {/* Usage hint — bottom-centre */}
        <Panel position="bottom-center">
          <div className="text-[10px] text-gray-700 bg-[#080a0f]/80 px-3 py-1 rounded-full border border-white/[0.04]">
            {isLocked
              ? '🔒 Read-only — pause the route to make changes'
              : 'Drag nodes from the palette · Click to configure · Connect handles · Delete / Backspace to remove'
            }
          </div>
        </Panel>
      </ReactFlow>

      {/* Properties Drawer */}
      <PropertiesDrawer route={route} />

      {/* Filter Picker Modal (shown on filter node drop) */}
      {pendingDrop && (
        <FilterPickerModal
          filterType={pendingDrop.filterType}
          inferredPhase={pendingDrop.inferredPhase}
          inferredOrder={pendingDrop.inferredOrder}
          onAttach={(req) => attachMutation.mutate(req)}
          onClose={() => setPendingDrop(null)}
          isPending={attachMutation.isPending}
        />
      )}
    </div>
  )
}

// ─── Default node data for structural palette drops ───────────────────────────

function defaultDataForType(
  nodeType: string,
  _onDetach: (id: string) => void,
  onSelect: (id: string) => void,
): Record<string, unknown> {
  switch (nodeType) {
    case 'routeNode':
      return { name: 'Route Trigger', pathPattern: '/new/**', methods: 'GET', status: 'DRAFT', version: 1, onSelect: () => onSelect('') }
    case 'upstreamNode':
      return { uri: 'http://service:8080', onSelect: () => onSelect('') }
    case 'clientNode':
      return { status: 'DRAFT' }
    case 'responseNode':
      return { status: 'DRAFT' }
    default:
      return {}
  }
}
