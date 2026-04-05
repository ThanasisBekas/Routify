/**
 * Button — shared button primitive for Routify Dashboard.
 *
 * Variants:
 *  - `primary`  — indigo filled (default, main actions)
 *  - `secondary` — ghost/transparent (cancel, dismiss)
 *  - `danger`   — red filled (destructive actions)
 *  - `outline`  — bordered transparent (alternative actions)
 *
 * Sizes: `sm`, `md` (default), `lg`
 *
 * Supports loading spinner via `loading` prop.
 */
import React from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '../../lib/utils'

const variants = {
  primary:
    'bg-indigo-600 hover:bg-indigo-500 text-white shadow-lg shadow-indigo-500/20 font-semibold',
  secondary:
    'text-gray-400 hover:text-white hover:bg-white/[0.04] font-medium',
  danger:
    'bg-red-600 hover:bg-red-500 text-white shadow-lg shadow-red-500/20 font-semibold',
  outline:
    'border border-white/[0.08] text-gray-300 hover:text-white hover:bg-white/[0.04] hover:border-white/20 font-medium',
} as const

const sizes = {
  sm: 'px-3 py-1.5 text-xs rounded-lg gap-1.5',
  md: 'px-4 py-2 text-sm rounded-lg gap-2',
  lg: 'px-5 py-2.5 text-sm rounded-xl gap-2',
} as const

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: keyof typeof variants
  size?: keyof typeof sizes
  loading?: boolean
  icon?: React.ReactNode
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      variant = 'primary',
      size = 'md',
      loading = false,
      icon,
      disabled,
      className,
      children,
      ...props
    },
    ref,
  ) => (
    <button
      ref={ref}
      type="button"
      disabled={disabled || loading}
      className={cn(
        'inline-flex items-center justify-center transition-all disabled:opacity-50 disabled:pointer-events-none',
        variants[variant],
        sizes[size],
        className,
      )}
      {...props}
    >
      {loading ? (
        <Loader2 className="w-3.5 h-3.5 animate-spin shrink-0" />
      ) : icon ? (
        <span className="shrink-0">{icon}</span>
      ) : null}
      {children}
    </button>
  ),
)

Button.displayName = 'Button'

