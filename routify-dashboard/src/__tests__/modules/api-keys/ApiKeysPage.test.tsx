import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import ApiKeysPage from '../../../modules/api-keys/ApiKeysPage'

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
        <ApiKeysPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ApiKeysPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        content: [
          { id: 'k1', name: 'GitOps Agent', prefix: 'rk_abc', status: 'ACTIVE', createdAt: '2025-01-01T00:00:00Z' },
          { id: 'k2', name: 'CI Pipeline', prefix: 'rk_def', status: 'REVOKED', createdAt: '2025-01-01T00:00:00Z' },
        ],
        totalElements: 2,
        page: 0,
        size: 20,
      },
    }))
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('API Keys')
  })

  it('renders the page header', () => {
    renderPage()
    expect(screen.getByText('API Keys')).toBeInTheDocument()
  })

  it('renders Create API Key button for admins', () => {
    renderPage()
    expect(screen.getByText('Create API Key')).toBeInTheDocument()
  })

  it('renders API key list on data load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('GitOps Agent')).toBeInTheDocument()
      expect(screen.getByText('CI Pipeline')).toBeInTheDocument()
    })
  })
})
