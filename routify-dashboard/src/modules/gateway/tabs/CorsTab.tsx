/**
 * CorsTab — CORS policy configuration.
 *
 * Redesigned with a master-enable toggle hero area, grouped list inputs
 * with inline placeholder guidance, and a live preview chip.
 */
import { useState } from 'react'
import { Globe } from 'lucide-react'
import type { GatewayCorsConfig } from '../../../types'
import {
  SectionHeader,
  ToggleRow,
  Field,
  SaveBar,
  inputCls,
  textareaCls,
  InfoBanner,
} from '../components/GatewayPrimitives'

interface Props {
  initial: GatewayCorsConfig
  onSave: (v: GatewayCorsConfig) => void
  isPending: boolean
}

export default function CorsTab({ initial, onSave, isPending }: Props) {
  const [cfg, setCfg] = useState<GatewayCorsConfig>(initial)
  const dirty = JSON.stringify(cfg) !== JSON.stringify(initial)

  const listInput = (
    label: string,
    key: keyof Pick<
      GatewayCorsConfig,
      'allowedOriginPatterns' | 'allowedMethods' | 'allowedHeaders' | 'exposedHeaders' | 'paths'
    >,
    placeholder: string,
    hint?: string,
  ) => (
    <Field label={label} hint={hint}>
      <textarea
        rows={3}
        value={(cfg[key] as string[]).join('\n')}
        onChange={(e) =>
          setCfg((p) => ({
            ...p,
            [key]: e.target.value
              .split('\n')
              .map((s) => s.trim())
              .filter(Boolean),
          }))
        }
        placeholder={placeholder}
        className={`${textareaCls} font-mono text-xs`}
      />
    </Field>
  )

  return (
    <div className="max-w-2xl">
      <SectionHeader
        icon={Globe}
        title="CORS Configuration"
        description="Control which origins, HTTP methods, and headers are permitted for cross-origin requests routed through the gateway."
      />

      {/* Master enable with visual callout */}
      <div
        className={`rounded-xl border px-4 py-4 mb-6 transition-colors ${
          cfg.enabled ? 'bg-emerald-500/5 border-emerald-500/20' : 'bg-white/[0.03] border-white/[0.06]'
        }`}
      >
        <ToggleRow
          label="CORS Enabled"
          description={
            cfg.enabled
              ? 'CORS preflight and headers are applied to matching requests'
              : 'CORS is disabled — all cross-origin requests will be rejected by browsers'
          }
          checked={cfg.enabled}
          onChange={(v) => setCfg((p) => ({ ...p, enabled: v }))}
        />
      </div>

      <div className="space-y-5">
        {listInput(
          'Allowed Origin Patterns',
          'allowedOriginPatterns',
          'https://app.example.com\nhttps://*.example.com',
          'One pattern per line. Wildcards supported, e.g. https://*.example.com',
        )}
        {listInput(
          'Allowed Methods',
          'allowedMethods',
          'GET\nPOST\nPUT\nDELETE\nPATCH\nOPTIONS',
          'One HTTP method per line',
        )}
        {listInput('Allowed Headers', 'allowedHeaders', '*', 'One header per line. Use * to allow all request headers')}
        {listInput(
          'Exposed Headers',
          'exposedHeaders',
          'X-Request-Id\nX-Correlation-Id',
          'Headers accessible to browser JavaScript after the response is received',
        )}
        {listInput('Apply to Paths', 'paths', '/**', 'URL path patterns this CORS config applies to — default /**')}

        <div className="grid grid-cols-2 gap-4">
          <Field label="Max Age (seconds)" hint="How long browsers cache preflight results. Recommended: 86400 (1 day)">
            <input
              type="number"
              min={0}
              value={cfg.maxAge}
              onChange={(e) => setCfg((p) => ({ ...p, maxAge: Number(e.target.value) }))}
              className={inputCls}
            />
          </Field>
          <div className="flex items-end">
            <ToggleRow
              label="Allow Credentials"
              description="Required for cookie / Authorization header cross-origin requests"
              checked={cfg.allowCredentials}
              onChange={(v) => setCfg((p) => ({ ...p, allowCredentials: v }))}
            />
          </div>
        </div>

        {cfg.allowCredentials && (
          <InfoBanner variant="warning">
            <strong>Allow Credentials</strong> requires that <code>allowedOriginPatterns</code> does{' '}
            <strong>not</strong> contain <code>*</code> — browsers reject credentialed requests with wildcard origins.
          </InfoBanner>
        )}
      </div>

      <SaveBar onSave={() => onSave(cfg)} isPending={isPending} dirty={dirty} />
    </div>
  )
}
