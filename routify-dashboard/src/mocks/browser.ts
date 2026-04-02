/**
 * MSW browser integration — starts the Service Worker in mock mode.
 *
 * Only imported when VITE_MOCK=true (i.e. `npm run dev:mock`).
 * Never bundled into the production build.
 */
import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'
import { wsHandlers } from './wsHandlers'

export const worker = setupWorker(...handlers, ...wsHandlers)
