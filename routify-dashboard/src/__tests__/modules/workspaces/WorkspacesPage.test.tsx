import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import WorkspacesPage from '../../../modules/workspaces/WorkspacesPage'

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
        <WorkspacesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('WorkspacesPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async (config) => {
      if (config.url?.includes('/usage')) {
        return { status: 200, data: { dailyUsage: [] } }
      }
      return {
        status: 200,
        data: {
          content: [
            { id: 't1', name: 'Acme Corp', slug: 'acme', plan: 'PRO', status: 'ACTIVE' },
            { id: 't2', name: 'Beta Inc', slug: 'beta', plan: 'ENTERPRISE', status: 'ACTIVE' },
          ],
          totalElements: 2,
          page: 0,
          size: 20,
        },
      }
    })
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('Workspaces')
  })

  it('renders the page header', () => {
    renderPage()
    expect(screen.getByText('Workspaces')).toBeInTheDocument()
  })

  it('renders New Workspace button', () => {
    renderPage()
    expect(screen.getByText('New Workspace')).toBeInTheDocument()
  })

  it('renders workspaces on data load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument()
      expect(screen.getByText('Beta Inc')).toBeInTheDocument()
    })
  })
})

