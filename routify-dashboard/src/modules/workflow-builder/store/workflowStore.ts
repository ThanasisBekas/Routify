/**
 * workflowStore.ts — Zustand store for the WorkflowBuilder canvas.
 *
 * Holds the live node/edge graph, selected-node tracking, dirty state,
 * and a seed action that initialises the canvas from a pre-built graph.
 *
 * Pattern mirrors authStore / wsStore: flat actions, no persistence
 * (the source-of-truth is the backend; we save explicitly via the toolbar).
 */
import { create } from 'zustand'
import type { Node, Edge } from '@xyflow/react'

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

  // ── Actions ────────────────────────────────────────────────────────────────
  setNodes: (nodes: Node[]) => void
  setEdges: (edges: Edge[]) => void
  selectNode: (id: string | null) => void
  markDirty: () => void
  markClean: () => void
  resetCanvas: () => void
  /**
   * Seed the canvas from a pre-built node/edge graph.
   * Called by WorkflowBuilderPage once the route query resolves,
   * and again after a save to resync with backend state.
   */
  initFromGraph: (routeId: string, nodes: Node[], edges: Edge[]) => void
}

export const useWorkflowStore = create<WorkflowState>((set) => ({
  nodes: [],
  edges: [],
  selectedNodeId: null,
  isDirty: false,
  routeId: null,

  setNodes: (nodes) => set({ nodes, isDirty: true }),
  setEdges: (edges) => set({ edges, isDirty: true }),

  selectNode: (id) => set({ selectedNodeId: id }),

  markDirty: () => set({ isDirty: true }),
  markClean: () => set({ isDirty: false }),

  resetCanvas: () => set({
    nodes: [],
    edges: [],
    selectedNodeId: null,
    isDirty: false,
    routeId: null,
  }),

  initFromGraph: (routeId, nodes, edges) =>
    set({ nodes, edges, routeId, isDirty: false, selectedNodeId: null }),
}))

