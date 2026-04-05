import { describe, it, expect } from 'vitest'
import { cn, extractApiError } from '../../lib/utils'

// ─── cn() ────────────────────────────────────────────────────────────────────

describe('cn()', () => {
  it('merges simple class names', () => {
    expect(cn('foo', 'bar')).toBe('foo bar')
  })

  it('handles conditional classes via clsx', () => {
    const isHidden = false
    expect(cn('base', isHidden && 'hidden', 'extra')).toBe('base extra')
  })

  it('deduplicates conflicting Tailwind classes via tailwind-merge', () => {
    expect(cn('px-2 py-1', 'px-4')).toBe('py-1 px-4')
  })

  it('handles array inputs', () => {
    expect(cn(['foo', 'bar'])).toBe('foo bar')
  })

  it('handles object inputs', () => {
    expect(cn({ active: true, disabled: false })).toBe('active')
  })

  it('returns empty string for no arguments', () => {
    expect(cn()).toBe('')
  })

  it('handles undefined and null inputs gracefully', () => {
    expect(cn(undefined, null, 'valid')).toBe('valid')
  })

  it('merges Tailwind responsive variants correctly', () => {
    expect(cn('text-sm', 'md:text-lg', 'text-base')).toBe('md:text-lg text-base')
  })
})

// ─── extractApiError() ───────────────────────────────────────────────────────

describe('extractApiError()', () => {
  it('returns detail from an RFC 9457 Problem Details response', () => {
    const error = {
      response: {
        data: {
          type: 'urn:routify:error:not-found',
          title: 'Not Found',
          status: 404,
          detail: 'Route abc-123 not found',
        },
        status: 404,
      },
      message: 'Request failed with status code 404',
    }
    expect(extractApiError(error)).toBe('Route abc-123 not found')
  })

  it('falls back to title when detail is missing', () => {
    const error = {
      response: {
        data: {
          type: 'urn:routify:error:conflict',
          title: 'Conflict',
          status: 409,
        },
        status: 409,
      },
      message: 'Request failed with status code 409',
    }
    expect(extractApiError(error)).toBe('Conflict')
  })

  it('falls back to Axios message when response data is empty', () => {
    const error = {
      response: {
        data: {},
        status: 500,
      },
      message: 'Request failed with status code 500',
    }
    expect(extractApiError(error)).toBe('Request failed with status code 500')
  })

  it('falls back to Axios message when no response at all (network error)', () => {
    const error = {
      message: 'Network Error',
    }
    expect(extractApiError(error)).toBe('Network Error')
  })

  it('returns default fallback for completely unknown error shape', () => {
    expect(extractApiError({})).toBe('An unexpected error occurred')
  })

  it('returns custom fallback message when provided', () => {
    expect(extractApiError({}, 'Something broke')).toBe('Something broke')
  })

  it('returns fallback for null error', () => {
    expect(extractApiError(null)).toBe('An unexpected error occurred')
  })

  it('returns fallback for undefined error', () => {
    expect(extractApiError(undefined)).toBe('An unexpected error occurred')
  })

  it('prefers detail over title when both are present', () => {
    const error = {
      response: {
        data: {
          title: 'Validation Error',
          detail: 'Field "name" must not be blank',
          status: 400,
        },
      },
    }
    expect(extractApiError(error)).toBe('Field "name" must not be blank')
  })

  it('handles response with data as a string (non-JSON error page)', () => {
    const error = {
      response: {
        data: 'Internal Server Error',
        status: 500,
      },
      message: 'Request failed with status code 500',
    }
    // data is a string, so data.detail is undefined, falls through to message
    expect(extractApiError(error)).toBe('Request failed with status code 500')
  })
})

