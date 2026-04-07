import { useState, useCallback } from 'react'
import { X, Upload, AlertTriangle, Check, ArrowRight, FileCode } from 'lucide-react'
import { useMutation } from '@tanstack/react-query'
import { cn } from '../../lib/utils'
import { extractApiError } from '../../lib/utils'
import { exportImportApi } from '../../api/exportImportApi'
import { toast } from 'sonner'
import type { ImportPreviewResponse, DiffCreateEntry, DiffUpdateEntry } from '../../types'

interface Props {
  isOpen: boolean
  onClose: () => void
  onApplied: () => void
  file: File | null
}

/**
 * Import preview modal — shows a diff of what would change, with apply/cancel actions.
 *
 * Flow:
 * 1. File is read as text
 * 2. Preview endpoint is called (dry-run)
 * 3. Diff table is shown (green=create, yellow=update, gray=unchanged)
 * 4. User clicks "Apply" to commit the import
 */
export default function ImportPreviewModal({ isOpen, onClose, onApplied, file }: Props) {
  const [yamlContent, setYamlContent] = useState<string | null>(null)
  const [preview, setPreview] = useState<ImportPreviewResponse | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)

  // ─── Read file and call preview ─────────────────────────────────────────
  const previewMutation = useMutation({
    mutationFn: async (content: string) => {
      return exportImportApi.previewImport(content)
    },
    onSuccess: (data) => {
      setPreview(data)
      setPreviewError(null)
    },
    onError: (err) => {
      setPreviewError(extractApiError(err))
      setPreview(null)
    },
  })

  const applyMutation = useMutation({
    mutationFn: async () => {
      if (!yamlContent) throw new Error('No content to import')
      return exportImportApi.applyImport(yamlContent)
    },
    onSuccess: (data) => {
      toast.success(data.message || 'Import applied successfully')
      onApplied()
      onClose()
    },
    onError: (err) => {
      toast.error(extractApiError(err))
    },
  })

  // Read the file when the modal opens
  const readFile = useCallback(() => {
    if (!file) return
    const reader = new FileReader()
    reader.onload = (e) => {
      const content = e.target?.result as string
      setYamlContent(content)
      previewMutation.mutate(content)
    }
    reader.readAsText(file)
  }, [file, previewMutation])

  // Trigger file read when modal opens with a file
  if (isOpen && file && !yamlContent && !previewMutation.isPending) {
    readFile()
  }

  if (!isOpen) return null

  const hasChanges =
    preview &&
    (preview.changes.filters.create.length > 0 ||
      preview.changes.filters.update.length > 0 ||
      preview.changes.routes.create.length > 0 ||
      preview.changes.routes.update.length > 0)

  const totalChanges = preview
    ? preview.changes.filters.create.length +
      preview.changes.filters.update.length +
      preview.changes.routes.create.length +
      preview.changes.routes.update.length
    : 0

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        role="presentation"
        onClick={onClose}
        onKeyDown={(e) => {
          if (e.key === 'Escape') onClose()
        }}
      />

      {/* Modal */}
      <div className="relative w-full max-w-2xl max-h-[80vh] bg-[#12141c] border border-white/[0.08] rounded-xl shadow-2xl flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <div className="flex items-center gap-3">
            <FileCode className="w-5 h-5 text-indigo-400" />
            <div>
              <h2 className="text-base font-semibold text-white">Import Configuration</h2>
              {file && <p className="text-xs text-gray-500 mt-0.5">{file.name}</p>}
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-gray-500 hover:text-gray-300 hover:bg-white/[0.05]"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
          {/* Loading state */}
          {previewMutation.isPending && (
            <div className="flex items-center gap-3 text-gray-400 py-8 justify-center">
              <div className="w-5 h-5 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
              Analyzing import file...
            </div>
          )}

          {/* Error state */}
          {previewError && (
            <div className="bg-red-500/10 border border-red-500/20 rounded-lg p-4">
              <div className="flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
                <div>
                  <p className="text-sm font-medium text-red-300">Import validation failed</p>
                  <p className="text-xs text-red-400 mt-1">{previewError}</p>
                </div>
              </div>
            </div>
          )}

          {/* Preview results */}
          {preview && (
            <>
              {/* Warnings */}
              {preview.warnings.length > 0 && (
                <div className="bg-amber-500/10 border border-amber-500/20 rounded-lg p-3">
                  <div className="flex items-start gap-2">
                    <AlertTriangle className="w-4 h-4 text-amber-400 mt-0.5 shrink-0" />
                    <div className="space-y-1">
                      {preview.warnings.map((w, i) => (
                        <p key={i} className="text-xs text-amber-300">
                          {w}
                        </p>
                      ))}
                    </div>
                  </div>
                </div>
              )}

              {/* Filters section */}
              <DiffSectionView title="Filters" section={preview.changes.filters} />

              {/* Routes section */}
              <DiffSectionView title="Routes" section={preview.changes.routes} />

              {/* Summary */}
              <div className="text-xs text-gray-500 pt-2 border-t border-white/[0.04]">
                {totalChanges > 0
                  ? `${totalChanges} change${totalChanges !== 1 ? 's' : ''} will be applied`
                  : 'No changes detected — configuration is already up to date'}
              </div>
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-white/[0.06]">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-gray-400 hover:text-gray-200 rounded-lg hover:bg-white/[0.05] transition-all"
          >
            Cancel
          </button>
          <button
            onClick={() => applyMutation.mutate()}
            disabled={!hasChanges || applyMutation.isPending}
            className={cn(
              'flex items-center gap-2 px-4 py-2 text-sm font-semibold rounded-lg transition-all',
              hasChanges
                ? 'bg-indigo-600 hover:bg-indigo-500 text-white shadow-lg shadow-indigo-500/20'
                : 'bg-white/[0.05] text-gray-500 cursor-not-allowed',
            )}
          >
            {applyMutation.isPending ? (
              <>
                <div className="w-3.5 h-3.5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                Applying...
              </>
            ) : (
              <>
                <Upload className="w-3.5 h-3.5" />
                Apply Import
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Diff Section Component ─────────────────────────────────────────────────

function DiffSectionView({ title, section }: { title: string; section: ImportPreviewResponse['changes']['filters'] }) {
  const total = section.create.length + section.update.length + section.unchanged.length
  if (total === 0) return null

  return (
    <div>
      <h3 className="text-sm font-semibold text-gray-300 mb-2">{title}</h3>
      <div className="space-y-1">
        {/* Creates — green */}
        {section.create.map((item: DiffCreateEntry) => (
          <div
            key={item.name}
            className="flex items-center gap-2 px-3 py-1.5 rounded-md bg-emerald-500/8 border border-emerald-500/15"
          >
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
            <span className="text-xs font-medium text-emerald-300">NEW</span>
            <span className="text-sm text-gray-200">{item.name}</span>
            <span className="text-xs text-gray-500 ml-auto">{item.type}</span>
          </div>
        ))}

        {/* Updates — yellow */}
        {section.update.map((item: DiffUpdateEntry) => (
          <div
            key={item.name}
            className="flex items-center gap-2 px-3 py-1.5 rounded-md bg-amber-500/8 border border-amber-500/15"
          >
            <span className="w-1.5 h-1.5 rounded-full bg-amber-400" />
            <span className="text-xs font-medium text-amber-300">UPD</span>
            <span className="text-sm text-gray-200">{item.name}</span>
            <div className="flex items-center gap-1 ml-auto">
              <ArrowRight className="w-3 h-3 text-gray-500" />
              <span className="text-xs text-gray-500">{item.changes.join(', ')}</span>
            </div>
          </div>
        ))}

        {/* Unchanged — gray */}
        {section.unchanged.map((name: string) => (
          <div
            key={name}
            className="flex items-center gap-2 px-3 py-1.5 rounded-md bg-white/[0.02] border border-white/[0.04]"
          >
            <Check className="w-3 h-3 text-gray-600" />
            <span className="text-sm text-gray-500">{name}</span>
            <span className="text-xs text-gray-600 ml-auto">unchanged</span>
          </div>
        ))}
      </div>
    </div>
  )
}
