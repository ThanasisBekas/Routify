import { Download, Upload, ChevronDown } from 'lucide-react'
import { useState, useRef } from 'react'
import { cn } from '../../lib/utils'

interface Props {
  onExport: (format: 'yaml' | 'json') => void
  onImportFile: (file: File) => void
  isExporting?: boolean
}

/**
 * Export dropdown + Import button for the routes page header.
 * Export triggers a download, Import opens a file picker dialog.
 */
export default function ImportExportButtons({ onExport, onImportFile, isExporting }: Props) {
  const [showExportMenu, setShowExportMenu] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  return (
    <div className="flex items-center gap-2">
      {/* ─── Export dropdown ──────────────────────────────────────────────── */}
      <div className="relative">
        <button
          onClick={() => setShowExportMenu(!showExportMenu)}
          disabled={isExporting}
          className={cn(
            'flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg transition-all border',
            'bg-white/[0.03] text-gray-300 border-white/[0.08] hover:bg-white/[0.06] hover:text-white',
            isExporting && 'opacity-50 cursor-not-allowed',
          )}
        >
          <Download className="w-3.5 h-3.5" />
          Export
          <ChevronDown className="w-3 h-3" />
        </button>

        {showExportMenu && (
          <>
            {/* Backdrop */}
            <div className="fixed inset-0 z-10" role="presentation" onClick={() => setShowExportMenu(false)} onKeyDown={(e) => { if (e.key === 'Escape') setShowExportMenu(false) }} />
            {/* Dropdown menu */}
            <div className="absolute right-0 mt-1 w-44 bg-[#1a1d27] border border-white/[0.08] rounded-lg shadow-xl z-20 py-1">
              <button
                onClick={() => {
                  onExport('yaml')
                  setShowExportMenu(false)
                }}
                className="w-full text-left px-3 py-2 text-sm text-gray-300 hover:bg-white/[0.06] hover:text-white transition-colors"
              >
                Export as YAML
              </button>
              <button
                onClick={() => {
                  onExport('json')
                  setShowExportMenu(false)
                }}
                className="w-full text-left px-3 py-2 text-sm text-gray-300 hover:bg-white/[0.06] hover:text-white transition-colors"
              >
                Export as JSON
              </button>
            </div>
          </>
        )}
      </div>

      {/* ─── Import button ────────────────────────────────────────────────── */}
      <button
        onClick={() => fileInputRef.current?.click()}
        className={cn(
          'flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg transition-all border',
          'bg-white/[0.03] text-gray-300 border-white/[0.08] hover:bg-white/[0.06] hover:text-white',
        )}
      >
        <Upload className="w-3.5 h-3.5" />
        Import
      </button>

      {/* Hidden file input */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".yaml,.yml,.json"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) {
            onImportFile(file)
            // Reset input so the same file can be re-imported
            e.target.value = ''
          }
        }}
      />
    </div>
  )
}

