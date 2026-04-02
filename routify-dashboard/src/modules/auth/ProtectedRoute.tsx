import { Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '../../store/authStore'

interface Props {
  children: React.ReactNode
  requiredRole?: string
}

/**
 * ProtectedRoute — redirects to /login if no valid access token in memory.
 * If the user has mustChangePassword=true they are redirected to /change-password
 * (the forced password-change wall) for every protected route except /change-password itself.
 */
export default function ProtectedRoute({ children, requiredRole }: Props) {
  const { isAuthenticated, user } = useAuthStore()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  // Block access to all protected pages until the user changes their password.
  if (user?.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />
  }

  if (requiredRole && user?.role !== requiredRole) {
    return (
      <div className="flex items-center justify-center h-full">
        <p className="text-red-400">Access Denied — insufficient role.</p>
      </div>
    )
  }

  return <>{children}</>
}

