import { Component, type ReactNode, type ErrorInfo } from 'react'

interface Props {
  children: ReactNode
  /** Optional custom fallback. Receives a `retry` callback to reset the boundary. */
  fallback?: ReactNode | ((props: { error: Error; retry: () => void }) => ReactNode)
  /** Optional label displayed above the error message (e.g. page name). */
  label?: string
}

interface State {
  hasError: boolean
  error: Error | null
  /** Incremented on retry — used as React key to force child remount. */
  retryCount: number
}

/**
 * React Error Boundary — catches unhandled exceptions in child component
 * trees and displays a fallback UI with a retry button instead of crashing
 * the entire application.
 *
 * Phase 5.4: Each module route in App.tsx is wrapped with its own
 * ErrorBoundary so a crash in one page doesn't take down the rest of the app.
 *
 * Usage:
 *   <ErrorBoundary>
 *     <SomePage />
 *   </ErrorBoundary>
 *
 *   <ErrorBoundary label="Routes" fallback={<CustomFallback />}>
 *     <SomePage />
 *   </ErrorBoundary>
 *
 *   <ErrorBoundary fallback={({ error, retry }) => <MyFallback error={error} onRetry={retry} />}>
 *     <SomePage />
 *   </ErrorBoundary>
 */
export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null, retryCount: 0 }
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Routify ErrorBoundary caught:', error, info)
  }

  private handleRetry = () => {
    this.setState((prev) => ({
      hasError: false,
      error: null,
      retryCount: prev.retryCount + 1,
    }))
  }

  render() {
    if (this.state.hasError) {
      const { fallback, label } = this.props
      const { error } = this.state

      // Render-prop style fallback
      if (typeof fallback === 'function') {
        return fallback({ error: error!, retry: this.handleRetry })
      }

      // Static ReactNode fallback
      if (fallback) {
        return fallback
      }

      // Default built-in fallback
      return (
        <div className="flex flex-col items-center justify-center h-64 gap-4">
          <div className="w-12 h-12 rounded-full bg-red-500/10 flex items-center justify-center">
            <svg className="w-6 h-6 text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"
              />
            </svg>
          </div>
          {label && <p className="text-gray-400 text-xs font-medium uppercase tracking-wider">{label}</p>}
          <p className="text-red-400 font-semibold text-sm">Something went wrong</p>
          <p className="text-gray-500 text-xs text-center max-w-sm">
            {error?.message || 'An unexpected error occurred'}
          </p>
          <button
            className="px-4 py-1.5 text-xs font-medium rounded-md border border-white/10 text-gray-300 hover:bg-white/5 transition-colors"
            onClick={this.handleRetry}
          >
            Try again
          </button>
        </div>
      )
    }

    // Key on retryCount to force a full remount of children after a retry.
    // flex-1 + flex-col + min-h-0 propagates the parent's height constraint so
    // pages like WorkflowBuilderPage resolve h-full to a definite height for
    // React Flow. Each page manages its own scrolling internally (e.g.
    // flex-1 overflow-auto), so <main> uses overflow-hidden.
    return (
      <div key={this.state.retryCount} className="flex-1 flex flex-col min-h-0">
        {this.props.children}
      </div>
    )
  }
}
