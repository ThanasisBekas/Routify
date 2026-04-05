import { useEffect } from 'react'

const BASE_TITLE = 'Routify'

/**
 * Sets the document title for the current page.
 *
 * @param title — Page-specific title (e.g. "Routes", "Audit Log").
 *                Rendered as `"Routes — Routify"`. Pass `undefined` or `''`
 *                to show just "Routify".
 *
 * Restores the base title on unmount so navigating away never leaves a stale title.
 */
export function useDocumentTitle(title?: string) {
  useEffect(() => {
    document.title = title ? `${title} — ${BASE_TITLE}` : BASE_TITLE
    return () => {
      document.title = BASE_TITLE
    }
  }, [title])
}

