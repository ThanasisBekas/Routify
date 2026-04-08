import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import RouteWorkflowPage from '../../../modules/routes/RouteWorkflowPage'

// Mock @xyflow/react to avoid DOM measurement issues in happy-dom
vi.mock('@xyflow/react', () => ({
  ReactFlow: ({ children }: { children?: React.ReactNode }) => <div data-testid="react-flow">{children}</div>,
  Background: () => null,
  Controls: () => null,
  MiniMap: () => null,
  Panel: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  useReactFlow: () => ({ fitView: vi.fn(), getNodes: () => [], getEdges: () => [] }),
  useNodesState: (nodes: unknown[]) => [nodes, vi.fn(), vi.fn()],
  useEdgesState: (edges: unknown[]) => [edges, vi.fn(), vi.fn()],
  Position: { Top: 'top', Bottom: 'bottom', Left: 'left', Right: 'right' },
  MarkerType: { ArrowClosed: 'arrowclosed' },
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
      <MemoryRouter>
        <RouteWorkflowPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RouteWorkflowPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        content: [
          {
            id: 'r1',
            name: 'Users API',
            path: '/api/users/**',
            upstreamUrl: 'http://users:8080',
            status: 'ACTIVE',
            environment: 'PRODUCTION',
            filterCount: 2,
          },
        ],
        totalElements: 1,
        page: 0,
        size: 20,
      },
    }))
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('Routes')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Routes')).toBeInTheDocument()
    })
  })

  it('renders New Route button', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('New Route')).toBeInTheDocument()
    })
  })
})
