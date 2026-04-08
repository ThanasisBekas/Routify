import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import UsersPage from '../../../modules/users/UsersPage'

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
        <UsersPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('UsersPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async (config) => {
      if (config.url?.includes('/tenants')) {
        return {
          status: 200,
          data: {
            content: [{ id: 't1', name: 'Acme', slug: 'acme', plan: 'PRO', status: 'ACTIVE' }],
            totalElements: 1,
          },
        }
      }
      return {
        status: 200,
        data: {
          content: [
            { id: 'u1', username: 'admin', email: 'admin@test.com', role: 'SUPER_ADMIN', enabled: true },
            { id: 'u2', username: 'viewer', email: 'viewer@test.com', role: 'VIEWER', enabled: true },
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
    expect(document.title).toContain('Users')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Users')).toBeInTheDocument()
    })
  })

  it('renders users on data load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('admin@test.com')).toBeInTheDocument()
      expect(screen.getByText('viewer@test.com')).toBeInTheDocument()
    })
  })
})
