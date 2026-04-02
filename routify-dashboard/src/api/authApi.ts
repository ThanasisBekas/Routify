import apiClient from './client'
import type { LoginResponse } from '../types'

export const authApi = {
  login: (username: string, password: string, tenantSlug: string) =>
    apiClient
      .post<LoginResponse>('/api/v1/auth/login', { username, password, tenantSlug })
      .then((r) => r.data),

  /**
   * Silent refresh — no body required.
   * Uses the shared apiClient (withCredentials: true) and a relative URL so the
   * request is same-origin in both dev (Vite proxy) and production.
   * The browser sends the HttpOnly `refresh_token` cookie automatically.
   * A new cookie with a rotated refresh token is written by the server response.
   */
  refresh: () =>
    apiClient
      .post<LoginResponse>('/api/v1/auth/refresh', {})
      .then((r) => r.data),

  logout: () =>
    apiClient.post('/api/v1/auth/logout').catch(() => {}),

  /**
   * Self-service password change.
   * The caller must provide their current password for re-authentication.
   * On success the `mustChangePassword` flag is cleared.
   */
  changePassword: (userId: string, currentPassword: string, newPassword: string) =>
    apiClient
      .post<{ success: boolean; message: string }>('/api/v1/auth/change-password', {
        userId,
        currentPassword,
        newPassword,
      })
      .then((r) => r.data),
}
