import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import FiltersPage from '../../../modules/filters/FiltersPage'

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
        <FiltersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('FiltersPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        content: [
          { id: 'f1', name: 'JWT Auth', type: 'AUTH_JWT', routeCount: 3, createdAt: '2025-01-01T00:00:00Z' },
          {
            id: 'f2',
            name: 'Rate Limit',
            type: 'RATE_LIMIT_FIXED_WINDOW',
            routeCount: 1,
            createdAt: '2025-01-01T00:00:00Z',
          },
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
    expect(document.title).toContain('Filters')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Filters')).toBeInTheDocument()
    })
  })

  it('renders New Filter button', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('New Filter')).toBeInTheDocument()
    })
  })

  it('renders filters on data load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('JWT Auth')).toBeInTheDocument()
      expect(screen.getByText('Rate Limit')).toBeInTheDocument()
    })
  })
})
