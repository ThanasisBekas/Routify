import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import GitOpsPage from '../../../modules/gitops/GitOpsPage'

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
        <GitOpsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('GitOpsPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async (config) => {
      if (config.url?.includes('/gitops/status')) {
        return {
          status: 200,
          data: {
            connected: true,
            repoUrl: 'https://github.com/acme/gateway-config',
            branch: 'main',
            lastSyncAt: '2025-01-01T12:00:00Z',
            lastOutcome: 'SUCCESS',
            dryRun: false,
          },
        }
      }
      if (config.url?.includes('/gitops/history')) {
        return {
          status: 200,
          data: [
            { outcome: 'SUCCESS', syncedAt: '2025-01-01T12:00:00Z', changes: 2 },
          ],
        }
      }
      return { status: 200, data: {} }
    })
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('GitOps')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('GitOps')).toBeInTheDocument()
    })
  })

  it('renders Sync Now button', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Sync Now')).toBeInTheDocument()
    })
  })

  it('renders status data on load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText(/main/)).toBeInTheDocument()
    })
  })
})
