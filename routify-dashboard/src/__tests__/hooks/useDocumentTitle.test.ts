import { renderHook, act } from '@testing-library/react'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'

const BASE_TITLE = 'Routify'

describe('useDocumentTitle', () => {
  beforeEach(() => {
    document.title = BASE_TITLE
  })

  it('sets document title with page name', () => {
    renderHook(() => useDocumentTitle('Routes'))
    expect(document.title).toBe('Routes — Routify')
  })

  it('sets just base title when given undefined', () => {
    renderHook(() => useDocumentTitle(undefined))
    expect(document.title).toBe(BASE_TITLE)
  })

  it('sets just base title when given empty string', () => {
    renderHook(() => useDocumentTitle(''))
    expect(document.title).toBe(BASE_TITLE)
  })

  it('restores base title on unmount', () => {
    const { unmount } = renderHook(() => useDocumentTitle('Settings'))
    expect(document.title).toBe('Settings — Routify')

    unmount()
    expect(document.title).toBe(BASE_TITLE)
  })

  it('updates title when the argument changes', () => {
    const { rerender } = renderHook(({ title }) => useDocumentTitle(title), {
      initialProps: { title: 'Routes' },
    })
    expect(document.title).toBe('Routes — Routify')

    rerender({ title: 'Audit Log' })
    expect(document.title).toBe('Audit Log — Routify')
  })

  it('sets correct title for each page', () => {
    const pages = [
      'Routes',
      'Route Builder',
      'Filters',
      'Audit Log',
      'Settings',
      'Users',
      'Gateway',
      'Certificate Vault',
      'Workspaces',
      'Login',
      'Change Password',
    ]

    for (const page of pages) {
      const { unmount } = renderHook(() => useDocumentTitle(page))
      expect(document.title).toBe(`${page} — Routify`)
      unmount()
    }
  })
})

