import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import AlertsPage from '../../../modules/alerts/AlertsPage'

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
        <AlertsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AlertsPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        content: [
          {
            id: 'a1',
            name: 'High Error Rate',
            metric: 'ERROR_RATE',
            threshold: 5,
            state: 'OK',
            severity: 'WARNING',
            enabled: true,
            muted: false,
          },
          {
            id: 'a2',
            name: 'P99 Latency',
            metric: 'P99_LATENCY',
            threshold: 2000,
            state: 'FIRING',
            severity: 'CRITICAL',
            enabled: true,
            muted: false,
          },
        ],
        totalElements: 2,
      },
    }))
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('Alerts')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Alert Rules')).toBeInTheDocument()
    })
  })

  it('renders the New Alert Rule button', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('New Alert Rule')).toBeInTheDocument()
    })
  })

  it('renders alert rules on data load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('High Error Rate')).toBeInTheDocument()
      expect(screen.getByText('P99 Latency')).toBeInTheDocument()
    })
  })
})
