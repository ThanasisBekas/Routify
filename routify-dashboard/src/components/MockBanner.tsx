/**
 * MockBanner — a persistent, dismissible banner shown in mock mode so you
 * always know you're looking at synthetic data, not the real backend.
 */
import { useState } from 'react'
import { FlaskConical, X, ChevronDown, ChevronUp } from 'lucide-react'

const CREDENTIALS = [
  { label: 'Workspace', value: 'routify' },
  { label: 'Username',  value: 'admin' },
  { label: 'Password',  value: 'routify_admin_2025' },
]

export default function MockBanner() {
  const [expanded, setExpanded]   = useState(false)
  const [dismissed, setDismissed] = useState(false)

  if (dismissed) return null

  return (
    <div className="relative z-50 bg-amber-500/10 border-b border-amber-500/30 text-amber-300 text-xs">
      <div className="flex items-center gap-2 px-4 py-2">
        <FlaskConical className="w-3.5 h-3.5 shrink-0 text-amber-400" />
        <span className="font-semibold text-amber-400">Mock Mode</span>
        <span className="text-amber-300/70">
          — No backend required. All data is synthetic and resets on page reload.
        </span>

        <button
          onClick={() => setExpanded(v => !v)}
          className="ml-auto flex items-center gap-1 text-amber-400/70 hover:text-amber-300 transition-colors"
          title={expanded ? 'Hide credentials' : 'Show mock credentials'}
        >
          {expanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
          credentials
        </button>

        <button
          onClick={() => setDismissed(true)}
          className="p-0.5 rounded hover:bg-amber-400/10 text-amber-400/60 hover:text-amber-300 transition-colors"
          title="Dismiss"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>

      {expanded && (
        <div className="px-4 pb-3 flex flex-wrap gap-4">
          {CREDENTIALS.map(({ label, value }) => (
            <div key={label} className="flex items-center gap-1.5">
              <span className="text-amber-500/70">{label}:</span>
              <code className="bg-amber-500/10 border border-amber-500/20 rounded px-1.5 py-0.5 text-amber-200 font-mono text-[11px]">
                {value}
              </code>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

