import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import AuditPage from '../../../modules/audit/AuditPage'

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
        <AuditPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AuditPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        content: [
          {
            id: 'ev1',
            eventType: 'ROUTE_CREATED',
            aggregateType: 'ROUTE',
            aggregateId: 'r1',
            occurredAt: '2025-01-01T12:00:00Z',
            actorId: 'u1',
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
    expect(document.title).toContain('Audit')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Audit')).toBeInTheDocument()
    })
  })

  it('renders audit events on data load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText(/ROUTE_CREATED/)).toBeInTheDocument()
    })
  })
})
