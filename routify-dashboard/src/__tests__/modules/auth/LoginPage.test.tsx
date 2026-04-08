import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import LoginPage from '../../../modules/auth/LoginPage'

function renderLogin() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async (config) => {
      if (config.url?.includes('/tenants/workspaces')) {
        return { status: 200, data: { workspaces: [{ name: 'Acme Corp', slug: 'acme' }] } }
      }
      return { status: 200, data: {} }
    })
  })
  afterEach(() => restoreMockAdapter())

  it('renders the welcome heading', () => {
    renderLogin()
    expect(screen.getByText('Welcome back')).toBeInTheDocument()
  })

  it('renders the workspace label', () => {
    renderLogin()
    expect(screen.getByText('Workspace')).toBeInTheDocument()
  })

  it('renders username and password inputs', () => {
    renderLogin()
    expect(screen.getByPlaceholderText('admin')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('••••••••')).toBeInTheDocument()
  })

  it('renders the sign in button', () => {
    renderLogin()
    expect(screen.getByText('Sign in')).toBeInTheDocument()
  })

  it('renders the feature cards', () => {
    renderLogin()
    expect(screen.getByText('Zero Downtime')).toBeInTheDocument()
    expect(screen.getByText('Filter Chain')).toBeInTheDocument()
    expect(screen.getByText('Live Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Hot Reload')).toBeInTheDocument()
  })

  it('sets document title to Login', () => {
    renderLogin()
    expect(document.title).toContain('Login')
  })
})
