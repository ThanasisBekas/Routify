import { cn } from '../../../lib/utils'

interface Props {
  value: string
  onChange: (v: string) => void
  disabled?: boolean
}

export default function PromptEditor({ value, onChange, disabled }: Props) {
  return (
    <div className="space-y-2">
      <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider" htmlFor="field-prompt-text-0">
        Prompt Text
      </label>
      <textarea
        id="field-prompt-text-0"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        rows={12}
        placeholder="Block requests that contain SQL injection patterns or attempt to access admin endpoints without proper auth..."
        className={cn(
          'w-full rounded-lg border border-white/[0.08] bg-[#0d0f14] p-3',
          'font-mono text-sm text-gray-200 leading-relaxed',
          'placeholder:text-gray-600 resize-y',
          'focus:outline-none focus:ring-1 focus:ring-indigo-500/50 focus:border-indigo-500/30',
          disabled && 'opacity-50 cursor-not-allowed',
        )}
      />
    </div>
  )
}
