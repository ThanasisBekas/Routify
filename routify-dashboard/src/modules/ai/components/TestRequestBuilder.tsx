import { useState } from 'react'
import { cn } from '../../../lib/utils'

interface TestRequest {
  method: string
  path: string
  queryString?: string
  clientIp?: string
  headers?: Record<string, string>
  bodyExcerpt?: string
}

interface Props {
  onRun: (req: TestRequest) => void
  isRunning: boolean
}

const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']

export default function TestRequestBuilder({ onRun, isRunning }: Props) {
  const [method, setMethod] = useState('POST')
  const [path, setPath] = useState('/api/v1/orders')
  const [headerKey, setHeaderKey] = useState('')
  const [headerVal, setHeaderVal] = useState('')
  const [headers, setHeaders] = useState<Record<string, string>>({})
  const [body, setBody] = useState('')

  const addHeader = () => {
    if (headerKey.trim()) {
      setHeaders((h) => ({ ...h, [headerKey.trim()]: headerVal }))
      setHeaderKey('')
      setHeaderVal('')
    }
  }

  const removeHeader = (key: string) => {
    setHeaders((h) => {
      const copy = { ...h }
      delete copy[key]
      return copy
    })
  }

  const handleRun = () => {
    onRun({
      method,
      path,
      headers: Object.keys(headers).length > 0 ? headers : undefined,
      bodyExcerpt: body || undefined,
    })
  }

  return (
    <div className="space-y-4">
      <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Test Request</label>

      {/* Method + Path */}
      <div className="flex gap-2">
        <select
          value={method}
          onChange={(e) => setMethod(e.target.value)}
          className="rounded-lg border border-white/[0.08] bg-[#0d0f14] px-3 py-2 text-sm text-gray-200 focus:outline-none focus:ring-1 focus:ring-indigo-500/50"
        >
          {HTTP_METHODS.map((m) => (
            <option key={m} value={m}>
              {m}
            </option>
          ))}
        </select>
        <input
          value={path}
          onChange={(e) => setPath(e.target.value)}
          placeholder="/api/v1/..."
          className="flex-1 rounded-lg border border-white/[0.08] bg-[#0d0f14] px-3 py-2 text-sm text-gray-200 placeholder:text-gray-600 focus:outline-none focus:ring-1 focus:ring-indigo-500/50"
        />
      </div>

      {/* Headers */}
      <div className="space-y-2">
        <span className="text-[11px] text-gray-500">Headers</span>
        {Object.entries(headers).map(([k, v]) => (
          <div key={k} className="flex items-center gap-2 text-xs">
            <span className="font-mono text-gray-300">{k}:</span>
            <span className="text-gray-400 truncate flex-1">{v}</span>
            <button onClick={() => removeHeader(k)} className="text-red-400 hover:text-red-300 text-[10px]">
              ×
            </button>
          </div>
        ))}
        <div className="flex gap-2">
          <input
            value={headerKey}
            onChange={(e) => setHeaderKey(e.target.value)}
            placeholder="Header name"
            className="flex-1 rounded border border-white/[0.06] bg-[#0d0f14] px-2 py-1 text-xs text-gray-300 placeholder:text-gray-600"
          />
          <input
            value={headerVal}
            onChange={(e) => setHeaderVal(e.target.value)}
            placeholder="Value"
            onKeyDown={(e) => e.key === 'Enter' && addHeader()}
            className="flex-1 rounded border border-white/[0.06] bg-[#0d0f14] px-2 py-1 text-xs text-gray-300 placeholder:text-gray-600"
          />
          <button
            onClick={addHeader}
            className="px-2 py-1 text-xs text-indigo-400 hover:text-indigo-300 border border-indigo-500/20 rounded"
          >
            +
          </button>
        </div>
      </div>

      {/* Body */}
      <div>
        <span className="text-[11px] text-gray-500">Body</span>
        <textarea
          value={body}
          onChange={(e) => setBody(e.target.value)}
          rows={4}
          placeholder='{"query": "SELECT * FROM users"}'
          className="w-full mt-1 rounded-lg border border-white/[0.08] bg-[#0d0f14] p-2 font-mono text-xs text-gray-300 placeholder:text-gray-600 resize-y focus:outline-none focus:ring-1 focus:ring-indigo-500/50"
        />
      </div>

      {/* Run button */}
      <button
        onClick={handleRun}
        disabled={isRunning || !path.trim()}
        className={cn(
          'w-full py-2.5 rounded-lg text-sm font-semibold transition-all',
          isRunning
            ? 'bg-indigo-500/10 text-indigo-400 border border-indigo-500/20 cursor-wait'
            : 'bg-indigo-600 text-white hover:bg-indigo-500 active:bg-indigo-700',
        )}
      >
        {isRunning ? (
          <span className="flex items-center justify-center gap-2">
            <span className="w-3.5 h-3.5 border-2 border-indigo-300/30 border-t-indigo-300 rounded-full animate-spin" />
            Evaluating…
          </span>
        ) : (
          'Run Test'
        )}
      </button>
    </div>
  )
}
