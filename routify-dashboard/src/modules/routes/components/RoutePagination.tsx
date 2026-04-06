import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '../../../lib/utils'

interface Props {
  page: number
  totalPages: number
  total: number
  onPage: (p: number) => void
}

/**
 * Prev / current / Next pagination bar.
 * Falls back to numbered buttons when totalPages ≤ 7 to maintain scanability.
 */
export default function RoutePagination({ page, totalPages, total, onPage }: Props) {
  if (totalPages <= 1) return null

  const showNumbered = totalPages <= 7

  return (
    <div className="flex items-center justify-between px-6 py-3 border-t border-white/[0.06] bg-[#0c0e14] text-sm shrink-0">
      <span className="text-xs text-gray-500">{total} total routes</span>

      <div className="flex items-center gap-1">
        {/* Prev */}
        <button
          onClick={() => onPage(page - 1)}
          disabled={page === 0}
          className="w-7 h-7 flex items-center justify-center rounded-md text-gray-500 hover:text-white hover:bg-white/[0.05] disabled:opacity-30 disabled:cursor-not-allowed transition-all"
        >
          <ChevronLeft className="w-3.5 h-3.5" />
        </button>

        {showNumbered ? (
          Array.from({ length: totalPages }, (_, i) => (
            <button
              key={i}
              onClick={() => onPage(i)}
              className={cn(
                'w-7 h-7 rounded-md text-xs font-medium transition-all',
                page === i
                  ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-500/20'
                  : 'text-gray-500 hover:text-white hover:bg-white/[0.05]',
              )}
            >
              {i + 1}
            </button>
          ))
        ) : (
          <span className="text-xs text-gray-400 px-2">
            Page <span className="font-semibold text-white">{page + 1}</span> of {totalPages}
          </span>
        )}

        {/* Next */}
        <button
          onClick={() => onPage(page + 1)}
          disabled={page >= totalPages - 1}
          className="w-7 h-7 flex items-center justify-center rounded-md text-gray-500 hover:text-white hover:bg-white/[0.05] disabled:opacity-30 disabled:cursor-not-allowed transition-all"
        >
          <ChevronRight className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  )
}
