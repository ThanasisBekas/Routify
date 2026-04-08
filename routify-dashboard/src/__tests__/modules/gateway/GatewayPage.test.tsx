import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import GatewayPage from '../../../modules/gateway/GatewayPage'

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
        <GatewayPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('GatewayPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        cors: { allowedOrigins: ['*'] },
        securityHeaders: {},
        rateLimitPolicies: [],
        circuitBreakerDefaults: {},
        resilienceDefaults: {},
        authProviders: [],
        downstreamCredentials: [],
        proxy: {},
        httpClient: {},
        tenantIsolation: {},
        globalFilterEntries: [],
      },
    }))
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('Gateway')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Gateway Configuration')).toBeInTheDocument()
    })
  })

  it('renders tab navigation', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Overview')).toBeInTheDocument()
      expect(screen.getAllByText('CORS').length).toBeGreaterThanOrEqual(1)
    })
  })
})
