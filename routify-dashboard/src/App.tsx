import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from 'sonner'

import AppLayout from './components/AppLayout'
import ProtectedRoute from './modules/auth/ProtectedRoute'
import LoginPage from './modules/auth/LoginPage'
import ChangePasswordPage from './modules/auth/ChangePasswordPage'
import RouteWorkflowPage from './modules/routes/RouteWorkflowPage'
import WorkflowBuilderPage from './modules/workflow-builder/WorkflowBuilderPage'
import FiltersPage from './modules/filters/FiltersPage'
import AuditPage from './modules/audit/AuditPage'
import SettingsPage from './modules/settings/SettingsPage'
import UsersPage from './modules/users/UsersPage'
import GatewayPage from './modules/gateway/GatewayPage'
import CertVaultPage from './modules/certificates/CertVaultPage'
import WorkspacesPage from './modules/workspaces/WorkspacesPage'
import { WebSocketProvider } from './components/WebSocketProvider'
import { useBootstrapAuth } from './hooks/useBootstrapAuth'
import MockBanner from './components/MockBanner'

const IS_MOCK = import.meta.env.VITE_MOCK === 'true'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})

function AppRoutes() {
  const bootstrapped = useBootstrapAuth()

  if (!bootstrapped) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center bg-[#080a0f] gap-3">
        <div className="w-7 h-7 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
        <p className="text-xs text-gray-600">Loading Routify…</p>
      </div>
    )
  }

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      {/*
        Change-password wall — requires auth (token in memory) but is NOT wrapped
        in AppLayout. Accessible even when mustChangePassword=true so the user can
        actually complete the form. ProtectedRoute redirects here automatically.
      */}
      <Route
        path="/change-password"
        element={
          <ProtectedRoute>
            <ChangePasswordPage forced />
          </ProtectedRoute>
        }
      />

      <Route
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        {/* Default → routes list */}
        <Route index element={<Navigate to="/routes" replace />} />

        {/* Routes — core of Routify */}
        <Route path="routes" element={<RouteWorkflowPage />} />

        {/* Workflow Builder — full-page node canvas for a single route */}
        <Route path="routes/:routeId/builder" element={<WorkflowBuilderPage />} />

        {/* Filters — reusable filter definitions */}
        <Route path="filters" element={<FiltersPage />} />

        {/* Gateway — full configuration management */}
        <Route path="gateway" element={<GatewayPage />} />

        {/* Certificate Vault — inbound TLS certificate storage */}
        <Route path="certificates" element={<CertVaultPage />} />

        {/* Audit log */}
        <Route path="audit" element={<AuditPage />} />

        {/* Users management */}
        <Route path="users" element={<UsersPage />} />

        {/* Settings */}
        <Route path="settings" element={<SettingsPage />} />

        {/* Workspaces — SUPER_ADMIN only (page enforces the guard internally) */}
        <Route path="workspaces" element={<WorkspacesPage />} />
      </Route>

      <Route path="*" element={<Navigate to="/routes" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <WebSocketProvider>
          {IS_MOCK && <MockBanner />}
          <AppRoutes />
        </WebSocketProvider>
      </BrowserRouter>
      <Toaster
        position="bottom-right"
        toastOptions={{
          classNames: {
            toast: 'bg-[#0d0f14] border border-white/10 text-white text-sm rounded-xl shadow-xl',
            description: 'text-gray-400 text-xs',
            error: '!border-red-500/30',
            success: '!border-emerald-500/30',
          },
        }}
      />
    </QueryClientProvider>
  )
}
