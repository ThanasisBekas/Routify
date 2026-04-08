import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import CertVaultPage from '../../../modules/certificates/CertVaultPage'

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
        <CertVaultPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CertVaultPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async (config) => {
      if (config.url?.includes('/stats')) {
        return { status: 200, data: { total: 5, active: 3, revoked: 1, expired: 1 } }
      }
      if (config.url?.includes('/cert-groups')) {
        return { status: 200, data: { content: [], totalElements: 0, page: 0, size: 20 } }
      }
      return {
        status: 200,
        data: {
          content: [
            { id: 'c1', alias: 'Primary TLS', status: 'ACTIVE', notAfter: '2026-01-01T00:00:00Z' },
          ],
          totalElements: 1,
          page: 0,
          size: 20,
        },
      }
    })
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('Certificate')
  })

  it('renders the page header', () => {
    renderPage()
    expect(screen.getByText('Certificate Vault')).toBeInTheDocument()
  })

  it('renders certificates on data load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Certificate Groups')).toBeInTheDocument()
    })
  })
})
