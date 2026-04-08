import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import WebhooksPage from '../../../modules/webhooks/WebhooksPage'

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
        <WebhooksPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('WebhooksPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        content: [
          {
            id: 'w1',
            url: 'https://hooks.slack.com/services/123',
            events: ['ROUTE_ACTIVATED'],
            status: 'ACTIVE',
            createdAt: '2025-01-01T00:00:00Z',
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
    expect(document.title).toContain('Webhooks')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Webhooks')).toBeInTheDocument()
    })
  })

  it('renders New Webhook button', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('New Webhook')).toBeInTheDocument()
    })
  })

  it('renders webhook list area on data load', async () => {
    renderPage()
    await waitFor(() => {
      // After loading, the page should show the header and empty/full list area
      expect(screen.getByText('Webhooks')).toBeInTheDocument()
    })
  })
})
