import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { useAuthStore } from './store/authStore'

async function prepare() {
  if (import.meta.env.VITE_MOCK === 'true') {
    // Dynamically import mock modules so they are never bundled in production
    const { worker } = await import('./mocks/browser')
    const { startMockWs } = await import('./mocks/mockWs')
    const { MOCK_TENANT_ID } = await import('./mocks/db')

    // Start the MSW service worker — bypass unhandled requests (e.g. Vite HMR)
    await worker.start({ onUnhandledRequest: 'bypass' })

    // Pre-seed auth store so useBootstrapAuth short-circuits on mount.
    // The mock /api/v1/auth/refresh handler also returns this same session,
    // so page reloads continue to work without hitting the real backend.
    useAuthStore.getState().setTokens('mock-access-token-super-admin')
    useAuthStore.getState().setUser({
      id: 'bbbbbbbb-0000-0000-0000-000000000001',
      tenantId: MOCK_TENANT_ID,
      username: 'admin',
      email: 'admin@routify.demo',
      role: 'SUPER_ADMIN',
    })

    // Start the WebSocket simulator (drives wsStore directly, no real socket)
    startMockWs()
  }
}

prepare().then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})
