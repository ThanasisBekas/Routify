/**
 * useBootstrapAuth — silently restores the user's session on page load.
 *
 * On every hard page refresh the in-memory Zustand store is reset — the access
 * token is gone. This hook fires once on mount and calls POST /api/v1/auth/refresh
 * with no body; the browser automatically sends the HttpOnly `refresh_token` cookie.
 *
 * If the cookie is present and valid the server returns a new access token and sets
 * a fresh rotated cookie.  If the cookie is absent or expired the server returns 401
 * and the user is redirected to /login by ProtectedRoute.
 *
 * In mock mode the store is pre-seeded before this hook runs — the `accessToken`
 * check short-circuits, skipping the network call entirely.
 */
import { useEffect, useState } from 'react'
import { authApi } from '../api/authApi'
import { useAuthStore } from '../store/authStore'

export function useBootstrapAuth() {
  const { accessToken, setTokens, setUser } = useAuthStore()
  const [bootstrapped, setBootstrapped] = useState(false)

  useEffect(() => {
    // Already have an access token in memory (mock mode, or login just happened)
    if (accessToken) {
      setBootstrapped(true)
      return
    }

    // Attempt silent refresh via the HttpOnly cookie.
    // If there is no valid cookie, the server returns 401 — we silently ignore it
    // and let ProtectedRoute redirect to /login.
    authApi
      .refresh()
      .then((data) => {
        setTokens(data.accessToken)
        if (data.user) setUser(data.user)
      })
      .catch(() => {
        // No valid session — user must log in.  ProtectedRoute handles the redirect.
      })
      .finally(() => setBootstrapped(true))

  // Run only once on mount
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return bootstrapped
}
