/**
 * Auth store — access token lives in memory only (never localStorage or sessionStorage).
 *
 * Security model:
 *  - accessToken  : Zustand memory only — cleared on page refresh, re-acquired via
 *                   silent refresh (HttpOnly cookie sent automatically by the browser).
 *  - refreshToken : HttpOnly; Secure; SameSite=Strict cookie — JS has zero access to it.
 *  - user         : Derived from the access token on login/refresh; never persisted.
 *
 * Nothing is written to localStorage.  The `persist` middleware is intentionally
 * configured with `partialize: () => ({})` (empty object) so the store key exists
 * but stores no data — this is a deliberate, documented no-op that satisfies the
 * Zustand persist middleware contract without actually persisting anything.
 */
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { UserInfo } from '../types'

interface AuthState {
  accessToken: string | null
  user: UserInfo | null
  isAuthenticated: boolean

  /** Called after a successful login or token refresh — access token only. */
  setTokens: (accessToken: string) => void
  setUser: (user: UserInfo) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      user: null,
      isAuthenticated: false,

      setTokens: (accessToken) =>
        set({ accessToken, isAuthenticated: true }),

      setUser: (user) => set({ user }),

      logout: () =>
        set({ accessToken: null, user: null, isAuthenticated: false }),
    }),
    {
      name: 'routify-auth',
      // Nothing is persisted — access token is memory-only; refresh token is
      // in the HttpOnly cookie managed entirely by the browser/server.
      partialize: () => ({}),
    },
  ),
)
