/**
 * Modal — shared overlay dialog primitive for Routify Dashboard.
 *
 * Features:
 *  - Full-screen backdrop with blur
 *  - Consistent header with icon, title, subtitle, and close button
 *  - Scrollable body area
 *  - Optional footer for action buttons
 *  - Closes on backdrop click (configurable)
 *  - Fade-in animation
 */
import React from 'react'
import { X } from 'lucide-react'
import { cn } from '../../lib/utils'

export interface ModalProps {
  open: boolean
  onClose: () => void
  children: React.ReactNode
  /** Max-width Tailwind class. Defaults to `max-w-lg`. */
  size?: string
  /** Set to false to prevent closing on backdrop click */
  closeOnBackdrop?: boolean
  className?: string
}

export function Modal({
  open,
  onClose,
  children,
  size = 'max-w-lg',
  closeOnBackdrop = true,
  className,
}: ModalProps) {
  if (!open) return null

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4 animate-fade-in"
      onClick={closeOnBackdrop ? onClose : undefined}
    >
      {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions */}
      <div
        role="dialog"
        aria-modal="true"
        className={cn(
          'bg-[#111318] border border-white/[0.08] rounded-2xl w-full shadow-2xl',
          size,
          className,
        )}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  )
}

/** Standard modal header with icon, title, optional subtitle, and close button. */
export function ModalHeader({
  icon,
  iconColor = 'indigo',
  title,
  subtitle,
  onClose,
}: {
  icon?: React.ReactNode
  iconColor?: 'indigo' | 'amber' | 'red' | 'emerald'
  title: string
  subtitle?: string
  onClose: () => void
}) {
  const iconColors = {
    indigo:  'bg-indigo-500/20 border-indigo-500/30',
    amber:   'bg-amber-500/20 border-amber-500/30',
    red:     'bg-red-500/20 border-red-500/30',
    emerald: 'bg-emerald-500/20 border-emerald-500/30',
  }

  return (
    <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
      <div className="flex items-center gap-2.5">
        {icon && (
          <div
            className={cn(
              'w-7 h-7 rounded-lg border flex items-center justify-center',
              iconColors[iconColor],
            )}
          >
            {icon}
          </div>
        )}
        <div>
          <h2 className="text-sm font-bold text-white">{title}</h2>
          {subtitle && (
            <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>
          )}
        </div>
      </div>
      <button
        type="button"
        onClick={onClose}
        className="p-1.5 rounded-lg text-gray-500 hover:text-white hover:bg-white/[0.05] transition-colors"
      >
        <X className="w-4 h-4" />
      </button>
    </div>
  )
}

/** Standard modal body — adds padding and optional scroll. */
export function ModalBody({
  children,
  className,
}: {
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('p-6', className)}>
      {children}
    </div>
  )
}

/** Standard modal footer — right-aligned action buttons. */
export function ModalFooter({
  children,
  className,
}: {
  children: React.ReactNode
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex items-center justify-end gap-2 px-6 py-4 border-t border-white/[0.06]',
        className,
      )}
    >
      {children}
    </div>
  )
}

