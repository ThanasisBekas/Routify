/**
 * WorkflowCanvas.tsx — The core React Flow canvas wrapper for the WorkflowBuilder.
 *
 * Responsibilities:
 *  - Consumes workflowStore for nodes / edges / selection
 *  - Accepts HTML5 drops from NodePalette and instantiates the correct node type
 *  - Wires onConnect to automatically colour-code new edges
 *  - Renders Background, Controls, MiniMap, ValidationBanner, AddFilterPanel
 *  - Exposes PropertiesDrawer as an overlay (absolutely positioned)
 *
 * Design notes:
 *  - Canvas background: near-black (#080a0f) with subtle dot grid
 *  - MiniMap uses the same dark tones as the rest of the UI
 *  - Connection line uses indigo to match the node accent
 */
import { useCallback, useMemo, useRef } from 'react'
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
  type NodeChange,
  type EdgeChange,
  type ReactFlowInstance,
  BackgroundVariant,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useQueryClient, useMutation } from '@tanstack/react-query'
import { routesApi } from '../../../api/routesApi'
import { useWorkflowStore } from '../store/workflowStore'
import { edgeStyle } from '../constants/nodeMetadata'
import { isFlowComplete } from '../hooks/buildGraph'
import { RouteTriggerNode } from '../nodes/RouteTriggerNode'
import {
  ClientNode, UpstreamNode, FilterNode, ResponseNode, LabelNode,
  AuthNode, RateLimitNode, ResilienceNode, TransformNode, ConditionalNode,
} from '../nodes/SharedNodes'
import ValidationBanner from './ValidationBanner'
import AddFilterPanel from './AddFilterPanel'
import PropertiesDrawer from './PropertiesDrawer'
import type { RouteDto, AttachFilterRequest } from '../../../types'

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
  authNode:      AuthNode,
  rateLimitNode: RateLimitNode,
  resilienceNode: ResilienceNode,
  transformNode:  TransformNode,
  conditionalNode: ConditionalNode,
}

// ─── Component ────────────────────────────────────────────────────────────────

interface WorkflowCanvasProps {
  route: RouteDto
}

export default function WorkflowCanvas({ route }: WorkflowCanvasProps) {
  const qc = useQueryClient()
  const rfInstance = useRef<ReactFlowInstance | null>(null)

  const {
    nodes, edges,
    setNodes, setEdges,
    selectNode,
  } = useWorkflowStore()

  // ── Mutations ──────────────────────────────────────────────────────────────

  const detachMutation = useMutation({
    mutationFn: (filterId: string) => routesApi.detachFilter(route.id, filterId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['route', route.id] }),
  })

  const attachMutation = useMutation({
    mutationFn: (req: AttachFilterRequest) => routesApi.attachFilter(route.id, req),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['route', route.id] }),
  })

  const onDetach = useCallback((id: string) => detachMutation.mutate(id), [detachMutation])
  const onSelect = useCallback((nodeId: string) => selectNode(nodeId), [selectNode])

  // ── React Flow event handlers ──────────────────────────────────────────────

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => {
      setNodes(applyNodeChanges(changes, nodes))
    },
    [nodes, setNodes],
  )

  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      setEdges(applyEdgeChanges(changes, edges))
    },
    [edges, setEdges],
  )

  const onConnect = useCallback(
    (params: Connection) => {
      // Derive edge colour from the source node's position in the pipeline
      const src = nodes.find(n => n.id === params.source)
      const type =
        src?.id === 'route'                                              ? 'route' :
        src?.id?.startsWith('post-') || params.target === 'response'   ? 'post'  : 'pre'
      const newEdges = addEdge({ ...params, ...edgeStyle(type) }, edges)
      setEdges(newEdges)
    },
    [nodes, edges, setEdges],
  )

  // ── Drag-and-drop from NodePalette ────────────────────────────────────────

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }, [])

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault()
      const nodeType = event.dataTransfer.getData('application/reactflow')
      if (!nodeType || !rfInstance.current) return

      // Convert screen coordinates to canvas coordinates
      const position = rfInstance.current.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      })

      const newNode = {
        id: `${nodeType}-${Date.now()}`,
        type: nodeType,
        position,
        data: defaultDataForType(nodeType, onDetach, onSelect),
      }

      setNodes([...nodes, newNode])
    },
    [nodes, setNodes, onDetach, onSelect],
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
  const attachedIds = useMemo(
    () => new Set((route.filters ?? []).map(f => f.filterId)),
    [route.filters],
  )

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    /*
     * `relative` is required so PropertiesDrawer can position itself
     * absolutely within this container without affecting page layout.
     */
    <div className="relative flex-1 h-full bg-[#080a0f]">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeClick={onNodeClick}
        onDrop={onDrop}
        onDragOver={onDragOver}
        onInit={onInit}
        fitView
        fitViewOptions={{ padding: 0.14 }}
        minZoom={0.1}
        maxZoom={2}
        deleteKeyCode="Delete"
        proOptions={{ hideAttribution: true }}
        className="bg-[#080a0f]"
        connectionLineStyle={{ stroke: '#6366f1', strokeWidth: 2, strokeDasharray: '6 3' }}
        defaultEdgeOptions={{ type: 'smoothstep' }}
      >
        {/* Dot-grid background — subtle depth cue */}
        <Background
          variant={BackgroundVariant.Dots}
          gap={24}
          size={1}
          color="rgba(255,255,255,0.04)"
        />

        {/* Zoom / pan controls — styled to match dark theme */}
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

        {/* Validation status + Add Filter — top-right corner */}
        <Panel position="top-right" className="flex items-center gap-2 pr-2 pt-2">
          <ValidationBanner valid={valid} />
          <AddFilterPanel
            attachedIds={attachedIds}
            onAttach={(req: AttachFilterRequest) => attachMutation.mutate(req)}
          />
        </Panel>

        {/* Usage hint — bottom-centre */}
        <Panel position="bottom-center">
          <div className="text-[10px] text-gray-700 bg-[#080a0f]/80 px-3 py-1 rounded-full border border-white/[0.04]">
            Drag nodes · Click to configure · Connect handles · Delete to remove edges
          </div>
        </Panel>
      </ReactFlow>

      {/* Properties Drawer — absolutely positioned overlay */}
      <PropertiesDrawer route={route} />
    </div>
  )
}

// ─── Default node data for palette drops ──────────────────────────────────────

function defaultDataForType(
  nodeType: string,
  onDetach: (id: string) => void,
  onSelect: (id: string) => void,
): Record<string, unknown> {
  // Palette drops create placeholder nodes — user configures via PropertiesDrawer
  switch (nodeType) {
    case 'routeNode':
      return { name: 'New Route', pathPattern: '/new/**', methods: 'GET', status: 'DRAFT', version: 1, onSelect: () => onSelect('') }
    case 'upstreamNode':
      return { uri: 'http://service:8080', onSelect: () => onSelect('') }
    case 'filterNode':
      return {
        filter: { filterId: `new-${Date.now()}`, filterName: 'New Filter', filterType: 'AUTH_JWT', order: 10, phase: 'PRE', enabled: true },
        phase: 'PRE',
        onDetach,
      }
    case 'authNode':
      return { label: 'Auth Filter' }
    case 'rateLimitNode':
      return { label: 'Rate Limit' }
    case 'resilienceNode':
      return { label: 'Resilience' }
    case 'transformNode':
      return { label: 'Transform' }
    case 'conditionalNode':
      return { label: 'Conditional' }
    default:
      return {}
  }
}

