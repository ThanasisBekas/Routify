import { lazy, Suspense } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Toaster } from 'sonner'

import AppLayout from './components/AppLayout'
import ProtectedRoute from './modules/auth/ProtectedRoute'
import LoginPage from './modules/auth/LoginPage'
import ChangePasswordPage from './modules/auth/ChangePasswordPage'
import { WebSocketProvider } from './components/WebSocketProvider'
import { useBootstrapAuth } from './hooks/useBootstrapAuth'
import { ErrorBoundary } from './components/ErrorBoundary'
import MockBanner from './components/MockBanner'

// ─── Lazy-loaded page modules ─────────────────────────────────────────────
// Each dynamic import() creates a separate chunk loaded on first navigation.
// Heaviest wins: WorkflowBuilderPage pulls in @xyflow/react, RouteWorkflowPage
// shares the same lib, GatewayPage/CertVaultPage have many sub-components.
const RouteWorkflowPage = lazy(() => import('./modules/routes/RouteWorkflowPage'))
const WorkflowBuilderPage = lazy(() => import('./modules/workflow-builder/WorkflowBuilderPage'))
const FiltersPage = lazy(() => import('./modules/filters/FiltersPage'))
const AuditPage = lazy(() => import('./modules/audit/AuditPage'))
const SettingsPage = lazy(() => import('./modules/settings/SettingsPage'))
const UsersPage = lazy(() => import('./modules/users/UsersPage'))
const GatewayPage = lazy(() => import('./modules/gateway/GatewayPage'))
const CertVaultPage = lazy(() => import('./modules/certificates/CertVaultPage'))
const WorkspacesPage = lazy(() => import('./modules/workspaces/WorkspacesPage'))
const ApiKeysPage = lazy(() => import('./modules/api-keys/ApiKeysPage'))

/** Minimal full-screen spinner shown while a lazy chunk is loading. */
function PageLoader() {
  return (
    <div className="flex-1 flex items-center justify-center">
      <div className="w-6 h-6 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
    </div>
  )
}

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
        <Route
          path="routes"
          element={
            <ErrorBoundary label="Routes">
              <Suspense fallback={<PageLoader />}>
                <RouteWorkflowPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* Workflow Builder — full-page node canvas for a single route */}
        <Route
          path="routes/:routeId/builder"
          element={
            <ErrorBoundary label="Route Builder">
              <Suspense fallback={<PageLoader />}>
                <WorkflowBuilderPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* Filters — reusable filter definitions */}
        <Route
          path="filters"
          element={
            <ErrorBoundary label="Filters">
              <Suspense fallback={<PageLoader />}>
                <FiltersPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* Gateway — full configuration management */}
        <Route
          path="gateway"
          element={
            <ErrorBoundary label="Gateway">
              <Suspense fallback={<PageLoader />}>
                <GatewayPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* Certificate Vault — inbound TLS certificate storage */}
        <Route
          path="certificates"
          element={
            <ErrorBoundary label="Certificate Vault">
              <Suspense fallback={<PageLoader />}>
                <CertVaultPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* Audit log */}
        <Route
          path="audit"
          element={
            <ErrorBoundary label="Audit Log">
              <Suspense fallback={<PageLoader />}>
                <AuditPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* Users management */}
        <Route
          path="users"
          element={
            <ErrorBoundary label="Users">
              <Suspense fallback={<PageLoader />}>
                <UsersPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* API Keys */}
        <Route
          path="api-keys"
          element={
            <ErrorBoundary label="API Keys">
              <Suspense fallback={<PageLoader />}>
                <ApiKeysPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* Settings */}
        <Route
          path="settings"
          element={
            <ErrorBoundary label="Settings">
              <Suspense fallback={<PageLoader />}>
                <SettingsPage />
              </Suspense>
            </ErrorBoundary>
          }
        />

        {/* Workspaces — SUPER_ADMIN only (page enforces the guard internally) */}
        <Route
          path="workspaces"
          element={
            <ErrorBoundary label="Workspaces">
              <Suspense fallback={<PageLoader />}>
                <WorkspacesPage />
              </Suspense>
            </ErrorBoundary>
          }
        />
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
