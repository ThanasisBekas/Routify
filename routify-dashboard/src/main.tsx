import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

async function bootstrap() {
  // Start MSW service worker when running in mock mode.
  // VITE_MOCK is set to "true" by the `.env.mock` file picked up by `dev:mock`.
  if (import.meta.env.VITE_MOCK === 'true') {
    const { worker } = await import('./mocks/browser')
    await worker.start({
      onUnhandledRequest: 'bypass', // pass-through non-API assets
      serviceWorker: { url: '/mockServiceWorker.js' },
    })
    console.info('[MSW] 🔶 Mock service worker active — all API calls are intercepted.')
  }
}

bootstrap().then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})
