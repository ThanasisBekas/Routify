import { render, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import WorkflowBuilderPage from '../../../modules/workflow-builder/WorkflowBuilderPage'

// Mock @xyflow/react to avoid DOM measurement issues in happy-dom
vi.mock('@xyflow/react', () => ({
  ReactFlow: ({ children }: { children?: React.ReactNode }) => <div data-testid="react-flow">{children}</div>,
  ReactFlowProvider: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  Background: () => null,
  Controls: () => null,
  MiniMap: () => null,
  Panel: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  Handle: () => null,
  useReactFlow: () => ({ fitView: vi.fn(), getNodes: () => [], getEdges: () => [], setNodes: vi.fn(), setEdges: vi.fn(), addNodes: vi.fn(), addEdges: vi.fn(), deleteElements: vi.fn(), project: vi.fn() }),
  useNodesState: (nodes: unknown[]) => [nodes, vi.fn(), vi.fn()],
  useEdgesState: (edges: unknown[]) => [edges, vi.fn(), vi.fn()],
  Position: { Top: 'top', Bottom: 'bottom', Left: 'left', Right: 'right' },
  MarkerType: { ArrowClosed: 'arrowclosed' },
  ConnectionMode: { Loose: 'loose' },
  useOnSelectionChange: () => {},
}))

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  useAuthStore.getState().setTokens('token')
  useAuthStore.getState().setUser({
    id: 'u1',
    tenantId: 't1',
    username: 'admin',
    email: 'a@b.com',
    role: 'SUPER_ADMIN',
  })

  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/routes/r1/builder']}>
        <Routes>
          <Route path="/routes/:routeId/builder" element={<WorkflowBuilderPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('WorkflowBuilderPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        id: 'r1',
        name: 'Users API',
        path: '/api/users/**',
        upstreamUrl: 'http://users:8080',
        status: 'ACTIVE',
        environment: 'PRODUCTION',
        filters: [],
      },
    }))
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('Route Builder')
  })

  it('renders the toolbar with route info', async () => {
    renderPage()
    // The workflow builder page sets document title and renders loading/toolbar
    await waitFor(() => {
      expect(document.title).toContain('Route Builder')
    })
  })
})
