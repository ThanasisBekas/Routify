/**
 * workflowStore.ts — Zustand store for the WorkflowBuilder canvas.
 *
 * Holds the live node/edge graph, selected-node tracking, dirty state,
 * single-use node tracking, filter count, and a seed action that
 * initialises the canvas from a pre-built graph.
 *
 * Pattern mirrors authStore / wsStore: flat actions, no persistence
 * (the source-of-truth is the backend; we save explicitly via the toolbar).
 *
 * Editing lock: when routeStatus === 'ACTIVE' all mutating actions are
 * hard-blocked in the store (setNodes, setEdges, clearCanvas) and the
 * canvas/palette/drawer mirror this by disabling their interactions.
 */
import { create } from 'zustand'
import type { Node, Edge } from '@xyflow/react'
import { inferExecutionOrder } from '../hooks/buildGraph'

/** Node types that may only appear once on the canvas (non-filter structural nodes) */
export const SINGLE_USE_NODE_TYPES = new Set(['clientNode', 'routeNode', 'upstreamNode', 'responseNode'])

/** Route status values that lock the canvas against edits */
const LOCKED_STATUSES = new Set(['ACTIVE'])

export interface WorkflowState {
  // ── Graph ──────────────────────────────────────────────────────────────────
  nodes: Node[]
  edges: Edge[]

  // ── Selection ──────────────────────────────────────────────────────────────
  selectedNodeId: string | null

  // ── Dirty flag ─────────────────────────────────────────────────────────────
  /** True when canvas has unsaved changes relative to last backend sync */
  isDirty: boolean

  // ── Current route context ──────────────────────────────────────────────────
  routeId: string | null

  /**
   * Mirrors route.status from the backend.  Kept in the store so that the
   * guard logic inside setNodes / setEdges / clearCanvas can reject mutations
   * without needing the route prop drilled into every action call site.
   */
  routeStatus: string | null

  // ── Single-use node tracking ───────────────────────────────────────────────
  /**
   * Set of node type strings (for structural nodes) AND filter type strings
   * (for filter nodes, prefixed with 'filter:') already present on the canvas.
   * E.g. 'clientNode', 'filter:AUTH_JWT'
   */
  usedNodeTypes: Set<string>

  // ── Filter count (for latency warning) ────────────────────────────────────
  filterCount: number

  // ── Derived execution order (inferred from graph topology) ─────────────────
  /** Ordered list of filter node IDs in execution sequence */
  executionOrder: string[]

  // ── Actions ────────────────────────────────────────────────────────────────
  setNodes: (nodes: Node[], action?: string) => void
  setEdges: (edges: Edge[], action?: string) => void
  selectNode: (id: string | null) => void
  markDirty: () => void
  markClean: () => void
  resetCanvas: () => void
  /**
   * Clear all nodes and edges from the canvas while keeping the routeId context.
   * Used by the "Clear Canvas" toolbar action — preserves the route association
   * so the user can rebuild from scratch without leaving the builder.
   * Blocked when routeStatus === 'ACTIVE'.
   */
  clearCanvas: () => void
  /**
   * Seed the canvas from a pre-built node/edge graph.
   * Called by WorkflowBuilderPage once the route query resolves,
   * and again after a save to resync with backend state.
   */
  initFromGraph: (routeId: string, routeStatus: string, nodes: Node[], edges: Edge[]) => void
  /** Keep routeStatus in sync whenever the polled route data changes */
  setRouteStatus: (status: string) => void
}

/** Derive which single-use node types are present, filter types in use, and filter count */
function deriveTracking(
  nodes: Node[],
  edges: Edge[],
): {
  usedNodeTypes: Set<string>
  filterCount: number
  executionOrder: string[]
} {
  const usedNodeTypes = new Set<string>()
  let filterCount = 0

  nodes.forEach((n) => {
    if (SINGLE_USE_NODE_TYPES.has(n.type ?? '')) {
      usedNodeTypes.add(n.type!)
    }
    if (n.type === 'filterNode') {
      filterCount++
      // Track filter type as 'filter:<filterType>' so NodePalette can disable duplicates
      const filterType = (n.data as { filter: { filterType: string } })?.filter?.filterType
      if (filterType) usedNodeTypes.add(`filter:${filterType}`)
    }
  })

  // Derive execution order from graph topology
  const inferred = inferExecutionOrder(nodes, edges)
  const executionOrder = inferred.map((e) => e.nodeId)

  return { usedNodeTypes, filterCount, executionOrder }
}

/** Returns true when the current routeStatus blocks mutations */
function isLocked(status: string | null): boolean {
  return LOCKED_STATUSES.has(status ?? '')
}

export const useWorkflowStore = create<WorkflowState>((set, get) => ({
  nodes: [],
  edges: [],
  selectedNodeId: null,
  isDirty: false,
  routeId: null,
  routeStatus: null,
  usedNodeTypes: new Set(),
  filterCount: 0,
  executionOrder: [],

  setNodes: (nodes, action = 'setNodes') => {
    const { routeId, routeStatus } = get()
    if (isLocked(routeStatus)) {
      console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId, action })
      return
    }
    set((state) => ({
      nodes,
      isDirty: true,
      ...deriveTracking(nodes, state.edges),
    }))
  },

  setEdges: (edges, action = 'setEdges') => {
    const { routeId, routeStatus } = get()
    if (isLocked(routeStatus)) {
      console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId, action })
      return
    }
    set((state) => ({
      edges,
      isDirty: true,
      // Recompute execution order when edges change (phase/order may shift)
      executionOrder: inferExecutionOrder(state.nodes, edges).map((e) => e.nodeId),
    }))
  },

  selectNode: (id) => {
    // Log node info on selection for debugging
    if (id !== null) {
      const { nodes, edges } = get()
      const node = nodes.find((n) => n.id === id)
      if (node?.type === 'filterNode') {
        const inferred = inferExecutionOrder(nodes, edges)
        const entry = inferred.find((e) => e.nodeId === id)
        console.log('[WorkflowBuilder] Node selected:', {
          nodeId: id,
          nodeType: node.type,
          computedPhase: entry?.phase ?? 'unconnected',
          computedOrder: entry?.order ?? -1,
        })
      }
    }
    set({ selectedNodeId: id })
  },

  markDirty: () => set({ isDirty: true }),
  markClean: () => set({ isDirty: false }),

  resetCanvas: () =>
    set({
      nodes: [],
      edges: [],
      selectedNodeId: null,
      isDirty: false,
      routeId: null,
      routeStatus: null,
      usedNodeTypes: new Set(),
      filterCount: 0,
      executionOrder: [],
    }),

  clearCanvas: () => {
    const { routeId, routeStatus } = get()
    if (isLocked(routeStatus)) {
      console.warn('[WorkflowBuilder] Edit blocked: route is active', { routeId, action: 'clearCanvas' })
      return
    }
    console.log('[WorkflowBuilder] Canvas cleared', { routeId })
    set({
      nodes: [],
      edges: [],
      selectedNodeId: null,
      isDirty: true,
      usedNodeTypes: new Set(),
      filterCount: 0,
      executionOrder: [],
      // routeId and routeStatus preserved — user stays in the same route's builder
    })
  },

  initFromGraph: (routeId, routeStatus, nodes, edges) =>
    set({
      nodes,
      edges,
      routeId,
      routeStatus,
      isDirty: false,
      selectedNodeId: null,
      ...deriveTracking(nodes, edges),
    }),

  setRouteStatus: (status) => set({ routeStatus: status }),
}))
