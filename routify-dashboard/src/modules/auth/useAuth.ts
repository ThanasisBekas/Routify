import { useAuthStore } from '../../store/authStore'
import { authApi } from '../../api/authApi'
import { useNavigate } from 'react-router-dom'

/**
 * useAuth — auth actions for Login page and nav bar.
 */
export function useAuth() {
  const { setTokens, setUser, logout, isAuthenticated, user } = useAuthStore()
  const navigate = useNavigate()

  const login = async (username: string, password: string, tenantSlug: string) => {
    const data = await authApi.login(username, password, tenantSlug)
    // Refresh token is in the HttpOnly cookie — we only store the access token
    setTokens(data.accessToken)
    if (data.user) setUser(data.user)
    // Redirect to change-password page if the account requires it
    if (data.mustChangePassword) {
      navigate('/change-password', { replace: true })
    } else {
      navigate('/routes', { replace: true })
    }
  }

  const doLogout = async () => {
    try {
      await authApi.logout()
    } finally {
      logout()
      navigate('/login', { replace: true })
    }
  }

  return { login, logout: doLogout, isAuthenticated, user }
}
