import { useRef, useEffect } from 'react'
import { AlertTriangle, Trash2, X } from 'lucide-react'

interface Props {
  routeName: string
  onConfirm: () => void
  onCancel: () => void
}

/**
 * A small inline confirmation popover that replaces window.confirm for route deletion.
 * Closes when the user clicks outside or presses Escape.
 */
export default function ConfirmDeletePopover({ routeName, onConfirm, onCancel }: Props) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onCancel()
    }
    const keyHandler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
    }
    document.addEventListener('mousedown', handler)
    document.addEventListener('keydown', keyHandler)
    return () => {
      document.removeEventListener('mousedown', handler)
      document.removeEventListener('keydown', keyHandler)
    }
  }, [onCancel])

  return (
    <div
      ref={ref}
      className="absolute right-0 bottom-full mb-2 z-50 w-64 bg-[#111318] border border-red-500/25 rounded-xl shadow-2xl p-4 animate-fade-in"
      onClick={e => e.stopPropagation()}
    >
      <div className="flex items-start gap-2.5 mb-3">
        <AlertTriangle className="w-4 h-4 text-red-400 shrink-0 mt-0.5" />
        <div>
          <p className="text-sm font-semibold text-white mb-0.5">Delete route?</p>
          <p className="text-xs text-gray-500 break-words">
            <span className="text-gray-300 font-medium">"{routeName}"</span> will be permanently removed.
          </p>
        </div>
      </div>
      <div className="flex gap-2 justify-end">
        <button
          onClick={onCancel}
          className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs text-gray-400 hover:text-white hover:bg-white/[0.05] border border-white/[0.07] transition-all"
        >
          <X className="w-3 h-3" />
          Cancel
        </button>
        <button
          onClick={onConfirm}
          className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-semibold text-white bg-red-600 hover:bg-red-500 border border-red-500/50 transition-all shadow-lg shadow-red-500/20"
        >
          <Trash2 className="w-3 h-3" />
          Delete
        </button>
      </div>
    </div>
  )
}

