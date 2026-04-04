/**
 * NetworkingTab — Upstream proxy + HTTP client (Reactor Netty) config.
 *
 * Redesigned with collapsible accordion sections and inline save buttons.
 */
import { useState } from 'react'
import { Network, ChevronDown } from 'lucide-react'
import type { GatewayProxyConfig, GatewayHttpClientConfig } from '../../../types'
import {
  SectionHeader, ToggleRow, Field, InlineSaveButton,
  inputCls, monoInputCls, textareaCls, InfoBanner,
} from '../components/GatewayPrimitives'
import { cn } from '../../../lib/utils'
import { Select } from '../../../components/ui/Select'

// ─── Collapsible section ──────────────────────────────────────────────────────

function Accordion({
  title,
  subtitle,
  defaultOpen = true,
  dirty,
  children,
}: {
  title: string
  subtitle?: string
  defaultOpen?: boolean
  dirty?: boolean
  children: React.ReactNode
}) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div className="rounded-xl border border-white/[0.06] overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center justify-between px-5 py-4 text-left hover:bg-white/[0.02] transition-colors"
      >
        <div>
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-white">{title}</span>
            {dirty && (
              <span className="w-1.5 h-1.5 rounded-full bg-amber-400 shrink-0" title="Unsaved changes" />
            )}
          </div>
          {subtitle && <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>}
        </div>
        <ChevronDown className={cn('w-4 h-4 text-gray-500 transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <div className="px-5 pb-5 border-t border-white/[0.05]">
          {children}
        </div>
      )}
    </div>
  )
}

// ─── Main tab ─────────────────────────────────────────────────────────────────

interface Props {
  initialProxy: GatewayProxyConfig
  initialHttp: GatewayHttpClientConfig
  onSaveProxy: (v: GatewayProxyConfig) => void
  onSaveHttp: (v: GatewayHttpClientConfig) => void
  isPending: boolean
}

export default function NetworkingTab({ initialProxy, initialHttp, onSaveProxy, onSaveHttp, isPending }: Props) {
  const [proxy, setProxy] = useState<GatewayProxyConfig>(initialProxy)
  const [http, setHttp] = useState<GatewayHttpClientConfig>(initialHttp)
  const dirtyProxy = JSON.stringify(proxy) !== JSON.stringify(initialProxy)
  const dirtyHttp  = JSON.stringify(http)  !== JSON.stringify(initialHttp)

  return (
    <div className="max-w-2xl space-y-4">
      <SectionHeader
        icon={Network}
        title="Networking"
        description="Configure the upstream HTTP/HTTPS/SOCKS5 proxy and Reactor Netty connection pool settings."
      />

      {/* ── Upstream Proxy ────────────────────────────────────────────────────── */}
      <Accordion
        title="Upstream Proxy"
        subtitle="Route all upstream gateway calls through an HTTP/HTTPS/SOCKS5 proxy"
        dirty={dirtyProxy}
      >
        <div className="pt-4 space-y-4">
          <div className={`rounded-xl border px-4 py-3 transition-colors ${
            proxy.enabled ? 'bg-amber-500/5 border-amber-500/20' : 'bg-white/[0.02] border-white/[0.05]'
          }`}>
            <ToggleRow
              label="Proxy Enabled"
              description={proxy.enabled
                ? 'All upstream requests are routed through the configured proxy'
                : 'Proxy disabled — upstream requests go directly to the internet'
              }
              checked={proxy.enabled}
              onChange={v => setProxy(p => ({ ...p, enabled: v }))}
            />
          </div>

          {proxy.enabled && (
            <div className="space-y-4">
              <div className="grid grid-cols-3 gap-4">
                <Field label="Proxy Type">
                  <Select
                    value={proxy.type}
                    onChange={v => setProxy(p => ({ ...p, type: v as GatewayProxyConfig['type'] }))}
                    options={['HTTP', 'HTTPS', 'SOCKS5'].map(t => ({ value: t, label: t }))}
                  />
                </Field>
                <Field label="Host">
                  <input value={proxy.host ?? ''} onChange={e => setProxy(p => ({ ...p, host: e.target.value }))}
                    placeholder="proxy.company.com" className={monoInputCls} />
                </Field>
                <Field label="Port">
                  <input type="number" value={proxy.port ?? ''} onChange={e => setProxy(p => ({ ...p, port: Number(e.target.value) }))} className={inputCls} />
                </Field>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Username" optional>
                  <input value={proxy.username ?? ''} onChange={e => setProxy(p => ({ ...p, username: e.target.value }))} className={inputCls} />
                </Field>
                <Field label="Password" optional>
                  <input type="password" value={proxy.password ?? ''} onChange={e => setProxy(p => ({ ...p, password: e.target.value }))} className={inputCls} />
                </Field>
              </div>
              <Field label="Non-Proxy Hosts" optional hint="One host pattern per line — these bypass the proxy">
                <textarea rows={3} value={(proxy.nonProxyHosts ?? []).join('\n')}
                  onChange={e => setProxy(p => ({ ...p, nonProxyHosts: e.target.value.split('\n').map(s => s.trim()).filter(Boolean) }))}
                  className={`${textareaCls} font-mono text-xs`} />
              </Field>
            </div>
          )}

          <InlineSaveButton onSave={() => onSaveProxy(proxy)} isPending={isPending} dirty={dirtyProxy} label="Apply Proxy Settings" />
        </div>
      </Accordion>

      {/* ── HTTP Client ───────────────────────────────────────────────────────── */}
      <Accordion
        title="HTTP Client (Reactor Netty)"
        subtitle="Connection pool, timeouts, and feature flags for all upstream HTTP calls"
        dirty={dirtyHttp}
      >
        <div className="pt-4 space-y-5">
          {/* Timeouts */}
          <div>
            <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-3">Timeouts</div>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Connect Timeout (ms)">
                <input type="number" value={http.connectTimeoutMs} onChange={e => setHttp(p => ({ ...p, connectTimeoutMs: Number(e.target.value) }))} className={inputCls} />
              </Field>
              <Field label="Response Timeout (ms)">
                <input type="number" value={http.responseTimeoutMs} onChange={e => setHttp(p => ({ ...p, responseTimeoutMs: Number(e.target.value) }))} className={inputCls} />
              </Field>
            </div>
          </div>

          {/* Connection pool */}
          <div>
            <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-3">Connection Pool</div>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Max Connections">
                <input type="number" value={http.maxConnections} onChange={e => setHttp(p => ({ ...p, maxConnections: Number(e.target.value) }))} className={inputCls} />
              </Field>
              <Field label="Max Per Route">
                <input type="number" value={http.maxConnectionsPerRoute} onChange={e => setHttp(p => ({ ...p, maxConnectionsPerRoute: Number(e.target.value) }))} className={inputCls} />
              </Field>
              <Field label="Acquire Timeout (ms)" hint="Max wait to borrow a connection from pool">
                <input type="number" value={http.acquireTimeoutMs} onChange={e => setHttp(p => ({ ...p, acquireTimeoutMs: Number(e.target.value) }))} className={inputCls} />
              </Field>
              <Field label="Max Idle Time" hint="e.g. 20s">
                <input value={http.maxIdleTime} onChange={e => setHttp(p => ({ ...p, maxIdleTime: e.target.value }))} className={monoInputCls} placeholder="20s" />
              </Field>
              <Field label="Max Life Time" hint="e.g. 60s">
                <input value={http.maxLifeTime} onChange={e => setHttp(p => ({ ...p, maxLifeTime: e.target.value }))} className={monoInputCls} placeholder="60s" />
              </Field>
            </div>
          </div>

          {/* Features */}
          <div>
            <div className="text-xs font-semibold text-gray-500 uppercase tracking-widest mb-1">Features</div>
            <div className="space-y-1">
              <ToggleRow label="GZip Compression" description="Compress upstream requests" checked={http.compressionEnabled} onChange={v => setHttp(p => ({ ...p, compressionEnabled: v }))} />
              <ToggleRow label="Follow Redirects" description="Automatically follow 3xx responses from upstream" checked={http.followRedirects} onChange={v => setHttp(p => ({ ...p, followRedirects: v }))} />
              <ToggleRow
                label="Wire Tap (debug)"
                description="⚠ Logs ALL request/response bytes to the gateway log — never enable in production"
                checked={http.wiretapEnabled}
                onChange={v => setHttp(p => ({ ...p, wiretapEnabled: v }))}
                danger={http.wiretapEnabled}
              />
            </div>
            {http.wiretapEnabled && (
              <InfoBanner variant="danger">
                <strong>Wire Tap is active.</strong> This logs all request and response bytes including credentials and sensitive data. Disable immediately after debugging.
              </InfoBanner>
            )}
          </div>

          <InlineSaveButton onSave={() => onSaveHttp(http)} isPending={isPending} dirty={dirtyHttp} label="Apply HTTP Client Settings" />
        </div>
      </Accordion>
    </div>
  )
}

