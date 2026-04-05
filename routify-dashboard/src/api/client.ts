/**
 * Axios instance with automatic JWT attachment and 401 → token refresh handling.
 *
 * Security model:
 *  - Access token  : stored in Zustand memory — injected into every request header.
 *  - Refresh token : HttpOnly cookie — the browser sends it automatically on POST
 *                    /api/v1/auth/refresh; JS code never reads or writes it.
 *
 * Refresh lock:
 *  If multiple requests return 401 simultaneously only ONE refresh call is made.
 *  All other failing requests are queued and replayed once the refresh resolves.
 *  This prevents race conditions that would invalidate the refresh token when
 *  token rotation is active.
 */
import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '../store/authStore'

// In mock mode use an empty base URL so all requests stay same-origin
// (http://localhost:5173/api/...) and the MSW service worker can intercept them.
// In production/dev-with-backend mode, use the configured API base URL which
// Vite proxies (or the browser sends directly in prod).
const BASE_URL =
  import.meta.env.VITE_MOCK === 'true' ? '' : (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8082')

// ─── Refresh lock state ───────────────────────────────────────────────────────
let isRefreshing = false
let refreshSubscribers: Array<(token: string) => void> = []

function onRefreshed(token: string) {
  refreshSubscribers.forEach((cb) => cb(token))
  refreshSubscribers = []
}

function subscribeToRefresh(cb: (token: string) => void) {
  refreshSubscribers.push(cb)
}

// ─── Axios instance ───────────────────────────────────────────────────────────

/**
 * Single Axios instance used by all API modules.
 * `withCredentials: true` is required so the browser includes the HttpOnly
 * refresh_token cookie when calling /api/v1/auth/refresh.
 */
export const apiClient: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000,
  withCredentials: true, // Send cookies (refresh_token) cross-origin
})

// ─── Request interceptor — inject access token + tenant ID ───────────────────
// X-Tenant-Id is required by the gateway's route Header predicate for every
// request. Without it, the gateway cannot match any tenant-scoped route and
// returns a 404. The value comes from the logged-in user's JWT claims which
// are stored in the auth store after login / silent refresh.
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const { accessToken, user } = useAuthStore.getState()

  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }

  // Inject tenant header unless the caller already set it explicitly
  // (e.g. certVaultApi passes a specific tenantId for cross-tenant admin calls).
  if (user?.tenantId && !config.headers['X-Tenant-Id']) {
    config.headers['X-Tenant-Id'] = user.tenantId
  }

  return config
})

// ─── Response interceptor — 401 → cookie-based token refresh ─────────────────
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config

    // Never attempt to refresh if the failing request IS the refresh endpoint —
    // that would cause an infinite refresh loop when the cookie is absent/expired.
    const isRefreshEndpoint = originalRequest?.url?.includes('/api/v1/auth/refresh')

    if (error.response?.status !== 401 || originalRequest._retry || isRefreshEndpoint) {
      return Promise.reject(error)
    }

    originalRequest._retry = true

    if (isRefreshing) {
      // Another refresh is already in flight — queue this request until it resolves
      return new Promise((resolve) => {
        subscribeToRefresh((token: string) => {
          originalRequest.headers.Authorization = `Bearer ${token}`
          resolve(apiClient(originalRequest))
        })
      })
    }

    isRefreshing = true

    try {
      // POST with no body — the HttpOnly cookie is sent automatically by the browser.
      // Use the apiClient (relative URL) so the request is same-origin in dev (Vite proxy)
      // and in production, which ensures the refresh_token cookie is always forwarded.
      const res = await apiClient.post('/api/v1/auth/refresh', {})
      const { accessToken } = res.data
      useAuthStore.getState().setTokens(accessToken)
      if (res.data.user) useAuthStore.getState().setUser(res.data.user)

      onRefreshed(accessToken)
      originalRequest.headers.Authorization = `Bearer ${accessToken}`
      return apiClient(originalRequest)
    } catch {
      // Refresh failed — session is dead; force logout
      refreshSubscribers = []
      useAuthStore.getState().logout()
      return Promise.reject(error)
    } finally {
      isRefreshing = false
    }
  },
)

export default apiClient
