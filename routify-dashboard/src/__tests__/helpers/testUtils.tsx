/**
 * Shared test utilities — mock adapter installer, wrapper factories, fixture builders.
 *
 * Use `installMockAdapter()` / `restoreMockAdapter()` in beforeEach/afterEach to
 * intercept Axios requests at the transport layer (after all interceptors run).
 */
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { AxiosRequestConfig } from 'axios'
import { apiClient } from '../../api/client'

// ─── Mock Adapter ──────────────────────────────────────────────────────────────

type MockResponse = {
  status: number
  data?: unknown
  headers?: Record<string, string>
}

type AdapterHandler = (config: AxiosRequestConfig) => Promise<MockResponse>

let adapterHandler: AdapterHandler = async () => ({ status: 200, data: {} })
const originalAdapter = apiClient.defaults.adapter

export function mockAdapter(handler: AdapterHandler) {
  adapterHandler = handler
}

export function installMockAdapter() {
  apiClient.defaults.adapter = async (config) => {
    const response = await adapterHandler(config as AxiosRequestConfig)
    return {
      data: response.data ?? {},
      status: response.status,
      statusText: response.status === 200 ? 'OK' : 'Error',
      headers: response.headers ?? {},
      config,
    } as never
  }
}

export function restoreMockAdapter() {
  apiClient.defaults.adapter = originalAdapter
}

// ─── Query wrapper ─────────────────────────────────────────────────────────────

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  })
}

/** Wrapper with QueryClientProvider + MemoryRouter for component tests. */
export function createWrapper(initialEntries: string[] = ['/']) {
  const qc = createTestQueryClient()
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={initialEntries}>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }
}

/** Wrapper with QueryClientProvider only (no router — for hooks). */
export function createQueryWrapper() {
  const qc = createTestQueryClient()
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

