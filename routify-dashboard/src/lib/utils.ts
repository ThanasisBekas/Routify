import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'
import type { AxiosError } from 'axios'
import type { ApiError } from '../types'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Extracts a human-readable message from an Axios error.
 * Prefers the backend's `detail` field (RFC 7807 Problem Details),
 * falls back to the HTTP status text, then to the JS error message.
 */
export function extractApiError(error: unknown, fallback = 'An unexpected error occurred'): string {
  const axiosError = error as AxiosError<ApiError>
  return (
    axiosError?.response?.data?.detail ??
    axiosError?.response?.data?.title ??
    axiosError?.message ??
    fallback
  )
}

