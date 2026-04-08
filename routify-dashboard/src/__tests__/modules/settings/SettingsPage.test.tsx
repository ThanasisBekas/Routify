import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import SettingsPage from '../../../modules/settings/SettingsPage'

function renderSettings() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  useAuthStore.getState().setTokens('token')
  useAuthStore.getState().setUser({
    id: 'u1',
    tenantId: 't1',
    username: 'admin',
    email: 'admin@routify.io',
    role: 'SUPER_ADMIN',
  })

  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SettingsPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
  })

  it('renders the settings page title', () => {
    renderSettings()
    expect(document.title).toContain('Settings')
  })

  it('displays the user profile section', () => {
    renderSettings()
    expect(screen.getAllByText('admin@routify.io').length).toBeGreaterThanOrEqual(1)
  })

  it('shows the role badge', () => {
    renderSettings()
    expect(screen.getByText('Super Admin')).toBeInTheDocument()
  })

  it('renders the platform info section', () => {
    renderSettings()
    expect(screen.getByText('2.0.2-SNAPSHOT')).toBeInTheDocument()
  })

  it('renders architecture section with Java 25', () => {
    renderSettings()
    expect(screen.getByText('Java 25')).toBeInTheDocument()
  })

  it('renders export/import section', () => {
    renderSettings()
    expect(screen.getByText('Export YAML')).toBeInTheDocument()
    expect(screen.getByText('Export JSON')).toBeInTheDocument()
  })

  it('renders security section with RBAC status', () => {
    renderSettings()
    // RBAC is disabled when user has no permissions
    expect(screen.getByText('Disabled')).toBeInTheDocument()
  })
})
