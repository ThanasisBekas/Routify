import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { installMockAdapter, restoreMockAdapter, mockAdapter } from '../../helpers/testUtils'
import AiPlaygroundPage from '../../../modules/ai/AiPlaygroundPage'

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
        <AiPlaygroundPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AiPlaygroundPage', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    installMockAdapter()
    mockAdapter(async () => ({
      status: 200,
      data: { content: [], totalElements: 0, page: 0, size: 100 },
    }))
  })
  afterEach(() => restoreMockAdapter())

  it('sets document title', () => {
    renderPage()
    expect(document.title).toContain('AI Playground')
  })

  it('renders the page header', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('AI Policy Playground')).toBeInTheDocument()
    })
  })

  it('renders the test policy button', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Run Test')).toBeInTheDocument()
    })
  })
})
