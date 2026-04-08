import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import RolesPage from '../../../modules/roles/RolesPage'

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
        <RolesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RolesPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: {
        content: [
          { id: 'r1', name: 'SUPER_ADMIN', description: 'Full access', builtIn: true, permissions: [] },
          { id: 'r2', name: 'Custom Ops', description: 'Custom operator role', builtIn: false, permissions: ['ROUTES_READ'] },
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
    expect(document.title).toContain('Roles')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Roles & Permissions')).toBeInTheDocument()
    })
  })

  it('renders Create Role button', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Create Role')).toBeInTheDocument()
    })
  })

  it('renders roles on data load', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('SUPER_ADMIN')).toBeInTheDocument()
      expect(screen.getByText('Custom Ops')).toBeInTheDocument()
    })
  })
})
