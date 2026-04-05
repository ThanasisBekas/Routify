/**
 * Card — shared container primitive for Routify Dashboard.
 *
 * Consistent dark-glass panel used for content sections, stat tiles,
 * and grouped form areas across all modules.
 */
import { cn } from '../../lib/utils'

export interface CardProps {
  children: React.ReactNode
  className?: string
  /** Add default padding. Defaults to `true`. */
  padded?: boolean
}

export function Card({ children, className, padded = true }: CardProps) {
  return (
    <div
      className={cn(
        'bg-white/[0.03] rounded-xl border border-white/[0.06]',
        padded && 'p-5',
        className,
      )}
    >
      {children}
    </div>
  )
}

