/**
 * Badge — shared pill-shaped label for statuses, roles, and tags.
 *
 * Colour presets cover the common domain statuses. Pass a custom `className`
 * for one-off colours.
 */
import { cn } from '../../lib/utils'

const colorPresets = {
  indigo: 'text-indigo-400  bg-indigo-400/10  border-indigo-400/20',
  emerald: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
  amber: 'text-amber-400   bg-amber-400/10   border-amber-400/20',
  red: 'text-red-400     bg-red-400/10     border-red-400/20',
  sky: 'text-sky-400     bg-sky-400/10     border-sky-400/20',
  purple: 'text-purple-400  bg-purple-400/10  border-purple-400/20',
  gray: 'text-gray-400    bg-gray-400/10    border-gray-400/20',
  rose: 'text-rose-400    bg-rose-400/10    border-rose-400/20',
  yellow: 'text-yellow-400  bg-yellow-400/10  border-yellow-400/20',
} as const

export interface BadgeProps {
  children: React.ReactNode
  color?: keyof typeof colorPresets
  /** Override size. Default is `sm`. */
  size?: 'xs' | 'sm'
  className?: string
  /** Optional leading icon element */
  icon?: React.ReactNode
}

export function Badge({ children, color = 'gray', size = 'sm', className, icon }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 font-semibold border rounded-full',
        size === 'xs' ? 'text-[10px] px-1.5 py-0.5' : 'text-[11px] px-2 py-0.5',
        colorPresets[color],
        className,
      )}
    >
      {icon}
      {children}
    </span>
  )
}
