import type { AxiosError } from 'axios'

/**
 * Extract a user-friendly error message from an unknown error (typically an AxiosError).
 * Falls back to the generic message if no API detail is available.
 */
export function extractApiError(e: unknown, fallback: string): string {
  if (e && typeof e === 'object' && 'response' in e) {
    const axiosErr = e as AxiosError<{ detail?: string; error?: string }>
    const data = axiosErr.response?.data
    return data?.detail ?? data?.error ?? axiosErr.message ?? fallback
  }
  if (e instanceof Error) return e.message
  return fallback
}

