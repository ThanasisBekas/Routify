import { render, screen, fireEvent } from '@testing-library/react'
import { ErrorBoundary } from '../../components/ErrorBoundary'

// ─── Helpers ────────────────────────────────────────────────────────────────

/** Always throws — for testing persistent errors. */
function AlwaysThrows(): JSX.Element {
  throw new Error('Persistent failure')
}

/** Suppress console.error noise from React's error boundary logging. */
let consoleErrorSpy: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => {
  consoleErrorSpy.mockRestore()
})

// ─── Tests ──────────────────────────────────────────────────────────────────

describe('ErrorBoundary', () => {
  describe('rendering children', () => {
    it('renders children when no error occurs', () => {
      render(
        <ErrorBoundary>
          <div data-testid="child">Hello</div>
        </ErrorBoundary>,
      )
      expect(screen.getByTestId('child')).toHaveTextContent('Hello')
    })

    it('does not show fallback UI when no error occurs', () => {
      render(
        <ErrorBoundary>
          <div>Healthy</div>
        </ErrorBoundary>,
      )
      expect(screen.queryByText('Something went wrong')).toBeNull()
    })
  })

  describe('default fallback', () => {
    it('shows default fallback on error', () => {
      render(
        <ErrorBoundary>
          <AlwaysThrows />
        </ErrorBoundary>,
      )
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })

    it('shows the error message', () => {
      render(
        <ErrorBoundary>
          <AlwaysThrows />
        </ErrorBoundary>,
      )
      expect(screen.getByText('Persistent failure')).toBeInTheDocument()
    })

    it('shows a "Try again" button', () => {
      render(
        <ErrorBoundary>
          <AlwaysThrows />
        </ErrorBoundary>,
      )
      expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
    })

    it('shows fallback message for error with no message', () => {
      function ThrowEmpty(): JSX.Element {
        throw new Error()
      }
      render(
        <ErrorBoundary>
          <ThrowEmpty />
        </ErrorBoundary>,
      )
      expect(screen.getByText('An unexpected error occurred')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
    })
  })

  describe('label prop', () => {
    it('displays the label above the error message', () => {
      render(
        <ErrorBoundary label="Routes">
          <AlwaysThrows />
        </ErrorBoundary>,
      )
      expect(screen.getByText('Routes')).toBeInTheDocument()
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })

    it('does not display a label when not provided', () => {
      render(
        <ErrorBoundary>
          <AlwaysThrows />
        </ErrorBoundary>,
      )
      // The label is rendered in a specific element with uppercase tracking
      const labels = screen.queryAllByText(/.*/, { selector: '.uppercase.tracking-wider' })
      expect(labels).toHaveLength(0)
    })
  })

  describe('retry / reset', () => {
    it('re-renders children after clicking "Try again"', () => {
      let shouldThrow = true
      function Conditional() {
        if (shouldThrow) throw new Error('First render fails')
        return <div data-testid="child">Recovered</div>
      }

      render(
        <ErrorBoundary>
          <Conditional />
        </ErrorBoundary>,
      )
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()

      // Fix the error condition before clicking retry
      shouldThrow = false
      fireEvent.click(screen.getByRole('button', { name: /try again/i }))

      expect(screen.getByTestId('child')).toHaveTextContent('Recovered')
      expect(screen.queryByText('Something went wrong')).toBeNull()
    })

    it('shows fallback again if retry also throws', () => {
      render(
        <ErrorBoundary>
          <AlwaysThrows />
        </ErrorBoundary>,
      )
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: /try again/i }))

      // Still in error state because AlwaysThrows keeps throwing
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })

    it('increments internal key to force child remount', () => {
      const mountSpy = vi.fn()
      let shouldThrow = true

      function Tracked() {
        mountSpy()
        if (shouldThrow) throw new Error('fail')
        return <div data-testid="child">OK</div>
      }

      render(
        <ErrorBoundary>
          <Tracked />
        </ErrorBoundary>,
      )
      // First render called mountSpy then threw
      const callsAfterError = mountSpy.mock.calls.length

      shouldThrow = false
      fireEvent.click(screen.getByRole('button', { name: /try again/i }))

      // After retry, component should have been re-mounted (mountSpy called again)
      expect(mountSpy.mock.calls.length).toBeGreaterThan(callsAfterError)
      expect(screen.getByTestId('child')).toBeInTheDocument()
    })
  })

  describe('custom fallback', () => {
    it('renders a static ReactNode fallback', () => {
      render(
        <ErrorBoundary fallback={<div data-testid="custom">Custom error</div>}>
          <AlwaysThrows />
        </ErrorBoundary>,
      )
      expect(screen.getByTestId('custom')).toHaveTextContent('Custom error')
      expect(screen.queryByText('Something went wrong')).toBeNull()
    })

    it('renders a render-prop fallback with error and retry', () => {
      let shouldThrow = true
      function Conditional() {
        if (shouldThrow) throw new Error('render-prop test')
        return <div data-testid="child">Works</div>
      }

      render(
        <ErrorBoundary
          fallback={({ error, retry }) => (
            <div>
              <span data-testid="rp-error">{error.message}</span>
              <button data-testid="rp-retry" onClick={retry}>
                Retry
              </button>
            </div>
          )}
        >
          <Conditional />
        </ErrorBoundary>,
      )

      expect(screen.getByTestId('rp-error')).toHaveTextContent('render-prop test')

      shouldThrow = false
      fireEvent.click(screen.getByTestId('rp-retry'))

      expect(screen.getByTestId('child')).toHaveTextContent('Works')
    })
  })

  describe('logging', () => {
    it('logs the caught error to console.error', () => {
      render(
        <ErrorBoundary>
          <AlwaysThrows />
        </ErrorBoundary>,
      )
      expect(consoleErrorSpy).toHaveBeenCalled()
      // React's error boundary calls console.error multiple times;
      // verify at least one call includes our prefix
      const calls = consoleErrorSpy.mock.calls.map((c) => c.join(' '))
      const hasRoutifyLog = calls.some((c) => c.includes('Routify ErrorBoundary caught:'))
      expect(hasRoutifyLog).toBe(true)
    })
  })

  describe('isolation', () => {
    it('does not affect sibling components outside the boundary', () => {
      render(
        <div>
          <div data-testid="sibling">Unaffected</div>
          <ErrorBoundary>
            <AlwaysThrows />
          </ErrorBoundary>
        </div>,
      )
      expect(screen.getByTestId('sibling')).toHaveTextContent('Unaffected')
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    })

    it('two boundaries crash independently', () => {
      render(
        <div>
          <ErrorBoundary label="A">
            <AlwaysThrows />
          </ErrorBoundary>
          <ErrorBoundary label="B">
            <div data-testid="b-child">Fine</div>
          </ErrorBoundary>
        </div>,
      )
      expect(screen.getByText('A')).toBeInTheDocument()
      expect(screen.getByText('Something went wrong')).toBeInTheDocument()
      expect(screen.getByTestId('b-child')).toHaveTextContent('Fine')
    })
  })
})
