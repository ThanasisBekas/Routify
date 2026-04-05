/**
 * Input — shared text input primitive for Routify Dashboard.
 *
 * Dark-theme styled input matching the platform design language.
 * Supports `mono` prop for monospace inputs (API keys, code values).
 * Forward-refs for React Hook Form compatibility.
 */
import React from 'react'
import { cn } from '../../lib/utils'

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  /** Use monospace font (e.g. API keys, JSON paths, code snippets) */
  mono?: boolean
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ mono, className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white',
        'placeholder-gray-600 transition-all',
        'focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30',
        'disabled:opacity-50 disabled:cursor-not-allowed',
        mono && 'font-mono text-xs',
        className,
      )}
      {...props}
    />
  ),
)

Input.displayName = 'Input'

/**
 * Textarea — shared multi-line input primitive.
 */
export interface TextareaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  mono?: boolean
}

export const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ mono, className, ...props }, ref) => (
    <textarea
      ref={ref}
      className={cn(
        'w-full bg-white/[0.04] border border-white/[0.08] rounded-lg px-3 py-2.5 text-sm text-white',
        'placeholder-gray-600 transition-all resize-none',
        'focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30',
        'disabled:opacity-50 disabled:cursor-not-allowed',
        mono && 'font-mono text-xs',
        className,
      )}
      {...props}
    />
  ),
)

Textarea.displayName = 'Textarea'

