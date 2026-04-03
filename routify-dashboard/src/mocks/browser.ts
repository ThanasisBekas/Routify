/**
 * browser.ts — MSW browser service worker setup.
 * Imported and started in main.tsx only when VITE_MOCK=true.
 */
import { setupWorker } from 'msw/browser'
import { handlers }    from './handlers'

export const worker = setupWorker(...handlers)

