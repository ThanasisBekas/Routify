/**
 * FilterConfigFields — renders type-specific form fields for each filter type.
 * Each filter section produces a strongly-typed config object so no raw JSON is needed.
 */
import { useState } from 'react'
import { cn } from '../../lib/utils'
import type { FilterType } from '../../types'
import type { AiModificationTestRequest, AiModificationTestResult } from '../../types'
import { Select } from '../../components/ui/Select'
import { inputCls, monoInputCls } from './filterConfigConstants'
import type { FilterConfig } from './filterConfigConstants'
import { aiApi } from '../../api/aiApi'

function Field({
  label,
  hint,
  error,
  children,
  optional,
}: {
  label: string
  hint?: string
  error?: string
  children: React.ReactNode
  optional?: boolean
}) {
  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <label className="text-xs font-semibold text-gray-400 uppercase tracking-wider">{label}</label>
        {optional && <span className="text-[10px] text-gray-600 normal-case font-normal">optional</span>}
      </div>
      {children}
      {hint && !error && <p className="text-[11px] text-gray-600">{hint}</p>}
      {error && <p className="text-xs text-red-400">{error}</p>}
    </div>
  )
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2 pt-2">
      <div className="h-px flex-1 bg-white/5" />
      <span className="text-[10px] font-bold text-gray-600 uppercase tracking-widest">{children}</span>
      <div className="h-px flex-1 bg-white/5" />
    </div>
  )
}

function Toggle({
  label,
  description,
  checked,
  onChange,
}: {
  label: string
  description?: string
  checked: boolean
  onChange: (v: boolean) => void
}) {
  return (
    <div className="flex items-center justify-between gap-4 px-3 py-3">
      <div className="min-w-0 flex-1">
        <div className="text-sm text-gray-300 leading-tight">{label}</div>
        {description && <div className="text-xs text-gray-500 mt-0.5 leading-snug">{description}</div>}
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={cn(
          'relative inline-flex shrink-0 h-6 w-11 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500/40',
          checked ? 'bg-indigo-600' : 'bg-gray-700',
        )}
      >
        <span
          className={cn(
            'inline-block h-4 w-4 rounded-full bg-white shadow-sm transition-transform duration-200',
            checked ? 'translate-x-6' : 'translate-x-1',
          )}
        />
      </button>
    </div>
  )
}

function TagInput({
  label,
  hint,
  values,
  onChange,
  placeholder,
  optional,
}: {
  label: string
  hint?: string
  values: string[]
  onChange: (v: string[]) => void
  placeholder?: string
  optional?: boolean
}) {
  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      const val = (e.currentTarget.value ?? '').trim()
      if (val && !values.includes(val)) {
        onChange([...values, val])
        e.currentTarget.value = ''
      }
    }
    if (e.key === 'Backspace' && e.currentTarget.value === '' && values.length > 0) {
      onChange(values.slice(0, -1))
    }
  }

  return (
    <Field label={label} hint={hint} optional={optional}>
      <div className="min-h-[40px] flex flex-wrap gap-1.5 bg-white/[0.04] border border-white/8 rounded-lg px-3 py-2 focus-within:border-indigo-500 transition-all">
        {values.map((v) => (
          <span
            key={v}
            className="flex items-center gap-1 text-xs bg-indigo-500/20 text-indigo-300 border border-indigo-500/30 px-2 py-0.5 rounded-full"
          >
            {v}
            <button
              type="button"
              onClick={() => onChange(values.filter((x) => x !== v))}
              className="text-indigo-400 hover:text-white transition-colors leading-none"
            >
              ×
            </button>
          </span>
        ))}
        <input
          type="text"
          onKeyDown={handleKeyDown}
          placeholder={values.length === 0 ? placeholder : 'Add more…'}
          className="flex-1 min-w-[120px] bg-transparent text-sm text-white placeholder-gray-600 focus:outline-none"
        />
      </div>
    </Field>
  )
}

// ─── Master renderer ──────────────────────────────────────────────────────────

interface Props {
  filterType: FilterType
  config: FilterConfig
  onChange: (config: FilterConfig) => void
}

export default function FilterConfigFields({ filterType, config, onChange }: Props) {
  const set = (key: string, value: unknown) => onChange({ ...config, [key]: value })

  const str = (key: string, fallback = '') => (config[key] as string) ?? fallback
  const num = (key: string, fallback = 0) => (config[key] as number) ?? fallback
  const bool = (key: string, fallback = false) => (config[key] as boolean) ?? fallback
  const arr = (key: string): string[] => (config[key] as string[]) ?? []

  switch (filterType) {
    // ── Authentication ────────────────────────────────────────────────────────
    case 'AUTH_JWT':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-blue-500/10 border border-blue-500/20 rounded-lg px-3 py-2 leading-relaxed">
            Validates the JWT bearer token from <code className="font-mono text-blue-300">Authorization: Bearer</code>{' '}
            header or <code className="font-mono text-blue-300">?token=</code> query param. On success injects{' '}
            <code className="font-mono text-blue-300">X-Auth-User-Id</code>,{' '}
            <code className="font-mono text-blue-300">X-Auth-Tenant-Id</code>,{' '}
            <code className="font-mono text-blue-300">X-Auth-Role</code> and{' '}
            <code className="font-mono text-blue-300">X-Auth-Email</code> downstream.
            Supports static RSA public key or <strong>JWKS URI</strong> for automatic key rotation.
            RS256 only. Issuer/audience claims are enforced when configured.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Issuer" hint="Expected iss claim value — leave blank to skip validation" optional>
              <input
                value={str('issuer')}
                onChange={(e) => set('issuer', e.target.value)}
                className={inputCls}
                placeholder="https://auth.example.com"
              />
            </Field>
            <Field label="Audience" hint="Expected aud claim value — leave blank to skip validation" optional>
              <input
                value={str('audience')}
                onChange={(e) => set('audience', e.target.value)}
                className={inputCls}
                placeholder="api://routify"
              />
            </Field>
          </div>
          <Toggle
            label="Require JTI claim"
            description="When enabled, tokens without a jti claim are rejected (recommended for blocklist enforcement)"
            checked={bool('requireJti', true)}
            onChange={(v) => set('requireJti', v)}
          />
        </div>
      )

    case 'AUTH_API_KEY':
      return (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Header Name" hint="Request header that carries the API key">
              <input
                value={str('headerName', 'X-API-Key')}
                onChange={(e) => set('headerName', e.target.value)}
                className={inputCls}
                placeholder="X-API-Key"
              />
            </Field>
            <Field label="Query Param" hint="Fallback query parameter (optional)" optional>
              <input
                value={str('queryParam')}
                onChange={(e) => set('queryParam', e.target.value)}
                className={inputCls}
                placeholder="api_key"
              />
            </Field>
          </div>
          <Field label="Validation Mode" hint="Where to validate the API key against">
            <Select
              value={str('validationMode', 'REDIS')}
              onChange={(v) => set('validationMode', v)}
              options={[
                { value: 'REDIS', label: 'Redis', description: 'Distributed — recommended for multi-node deployments' },
                {
                  value: 'REMOTE',
                  label: 'Remote (Identity Service)',
                  description: 'Delegate validation to the identity service',
                },
              ]}
            />
          </Field>
        </div>
      )

    case 'AUTH_BASIC':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-amber-500/10 border border-amber-500/20 rounded-lg px-3 py-2">
            Validates the client's <code className="font-mono text-amber-300">Authorization: Basic</code> header against
            the credentials below. On success injects <code className="font-mono text-amber-300">X-Auth-User-Id</code>{' '}
            and
            <code className="font-mono text-amber-300 ml-1">X-Auth-Type: BASIC</code> downstream.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Username" optional>
              <input
                value={str('username')}
                onChange={(e) => set('username', e.target.value)}
                className={inputCls}
                placeholder="admin"
                autoComplete="off"
              />
            </Field>
            <Field label="Password" optional>
              <input
                type="password"
                value={str('password')}
                onChange={(e) => set('password', e.target.value)}
                className={inputCls}
                placeholder="••••••••"
                autoComplete="new-password"
              />
            </Field>
          </div>
        </div>
      )

    case 'AUTH_OAUTH2':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-blue-500/10 border border-blue-500/20 rounded-lg px-3 py-2">
            Verifies the caller's Bearer token against an OAuth2 introspection endpoint. Claims are mapped to downstream
            request headers via <strong className="text-blue-300">Claims → Header Mapping</strong>.
          </p>
          <Field
            label="Provider Name"
            hint="Name of the oauth2Verification config entry in the gateway (auth.oauth2Verification.*)"
          >
            <input
              value={str('providerName')}
              onChange={(e) => set('providerName', e.target.value)}
              className={inputCls}
              placeholder="my-oauth2-provider"
            />
          </Field>
          <KeyValueFields
            label="Claims → Header Mapping"
            hint="Map token claim names to downstream request header names (e.g. sub → X-Auth-User-Id)"
            obj={(config.claimsToHeaderMapping as Record<string, string>) ?? {}}
            onChange={(v) => set('claimsToHeaderMapping', v)}
            optional
          />
        </div>
      )

    case 'AUTH_MTLS':
      return <MtlsMappingFields config={config} onChange={onChange} />

    case 'AUTH_CLIENT_ID':
      return <ClientIdMappingFields config={config} onChange={onChange} />

    case 'AUTH_CERT_VAULT':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-emerald-500/10 border border-emerald-500/20 rounded-lg px-3 py-2">
            Authenticates the caller by verifying their PEM certificate (from a request header) against the{' '}
            <strong className="text-emerald-300">Cert Vault</strong> registry. The cert must be ACTIVE and non-expired
            within the bound <strong className="text-emerald-300">Certificate Group</strong>. On success injects these
            headers downstream:
            <span className="block mt-1.5 space-x-1 font-mono text-[10px] text-emerald-400/80 leading-relaxed">
              X-Auth-Type · X-Auth-Cert-Logical-Id · X-Auth-Cert-Version · X-Auth-Cert-Fingerprint · X-Auth-Cert-Subject
              · X-Auth-Cert-CN · X-Auth-Cert-Issuer · X-Auth-Cert-Serial · X-Auth-Cert-Expiry
            </span>
          </p>

          <Field
            label="Group Logical ID"
            hint="The logicalId of the Certificate Group to scope the registry lookup to. Leave blank to scan all registered groups by fingerprint (useful for dynamic client registration)."
            optional
          >
            <input
              value={str('logicalId')}
              onChange={(e) => set('logicalId', e.target.value)}
              className={monoInputCls}
              placeholder="my-client-cert-group"
            />
          </Field>

          <Field label="Certificate Header" hint="Request header that carries the PEM-encoded client certificate">
            <input
              value={str('certificateHeader', 'X-Client-Certificate')}
              onChange={(e) => set('certificateHeader', e.target.value)}
              className={inputCls}
              placeholder="X-Client-Certificate"
            />
          </Field>

          <SectionTitle>Behaviour</SectionTitle>

          <Toggle
            label="Require Certificate"
            description="Reject with 401 if the certificate header is absent. When off, unauthenticated requests pass through (downstream services must make the final access decision)."
            checked={bool('requireCertificate', true)}
            onChange={(v) => set('requireCertificate', v)}
          />

          <Toggle
            label="Strip Certificate Header"
            description="Remove the raw certificate header before forwarding to the upstream service. Recommended to prevent leaking PEM material to downstream services."
            checked={bool('stripCertificateHeader', false)}
            onChange={(v) => set('stripCertificateHeader', v)}
          />
        </div>
      )

    // ── Rate Limiting ─────────────────────────────────────────────────────────
    case 'RATE_LIMIT_FIXED_WINDOW':
    case 'RATE_LIMIT_SLIDING_WINDOW': {
      const algo = filterType === 'RATE_LIMIT_FIXED_WINDOW' ? 'Fixed Window' : 'Sliding Window'
      return (
        <div className="space-y-4">
          <SectionTitle>{algo}</SectionTitle>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Max Requests" hint="Per window">
              <input
                type="number"
                min={1}
                value={num('maxRequests', 100)}
                onChange={(e) => set('maxRequests', +e.target.value)}
                className={inputCls}
              />
            </Field>
            <Field label="Window (ms)" hint="e.g. 60000 = 1 min">
              <input
                type="number"
                min={100}
                value={num('windowMs', 60000)}
                onChange={(e) => set('windowMs', +e.target.value)}
                className={inputCls}
              />
            </Field>
          </div>
          <Field label="Key Resolver" hint="How to identify the client for rate limiting">
            <Select
              value={str('keyResolver', 'IP')}
              onChange={(v) => set('keyResolver', v)}
              options={[
                { value: 'IP', label: 'IP Address' },
                { value: 'USER', label: 'Authenticated User (X-Auth-User-Id)' },
                { value: 'TENANT', label: 'Tenant (X-Tenant-Id)' },
                { value: 'API_KEY', label: 'API Key (X-API-Key)' },
                { value: 'TENANT_USER', label: 'Tenant + User' },
              ]}
            />
          </Field>
          <Toggle
            label="Include X-RateLimit-* Headers"
            description="Inject X-RateLimit-Limit, X-RateLimit-Remaining, and X-RateLimit-Reset headers on every response. Retry-After is always included on 429 regardless."
            checked={bool('includeHeaders', true)}
            onChange={(v) => set('includeHeaders', v)}
          />
        </div>
      )
    }

    // ── Header Modification ───────────────────────────────────────────────────
    case 'REQUEST_HEADER_MODIFY':
    case 'RESPONSE_HEADER_MODIFY':
      return (
        <HeaderModifyFields
          label={filterType === 'REQUEST_HEADER_MODIFY' ? 'Request' : 'Response'}
          config={config}
          onChange={onChange}
        />
      )

    // ── Body Transformation ───────────────────────────────────────────────────
    case 'BODY_JOLT_TRANSFORM':
      return (
        <div className="space-y-4">
          <Field label="Phase" hint="Which body direction to transform">
            <Select
              value={str('phase', 'REQUEST')}
              onChange={(v) => set('phase', v)}
              options={[
                { value: 'REQUEST', label: 'Request body' },
                { value: 'RESPONSE', label: 'Response body' },
                { value: 'BOTH', label: 'Both (request + response)' },
              ]}
            />
          </Field>
          <Field
            label={str('phase', 'REQUEST') === 'BOTH' ? 'Request Jolt Spec' : 'Jolt Spec'}
            hint={
              str('phase', 'REQUEST') === 'RESPONSE'
                ? 'Jolt Chainr transform spec for the response body (JSON array). If responseSpec is set below, it takes precedence.'
                : 'Jolt Chainr transform spec (JSON array)'
            }
          >
            <textarea
              value={str('spec', '[]')}
              onChange={(e) => set('spec', e.target.value)}
              rows={8}
              spellCheck={false}
              className={`${monoInputCls} resize-y`}
              placeholder={'[\n  {\n    "operation": "shift",\n    "spec": { ... }\n  }\n]'}
            />
          </Field>
          {(str('phase', 'REQUEST') === 'RESPONSE' || str('phase', 'REQUEST') === 'BOTH') && (
            <Field
              label="Response Jolt Spec"
              hint={
                str('phase', 'REQUEST') === 'BOTH'
                  ? 'Separate Jolt spec applied to the upstream response body (required for BOTH phase)'
                  : 'Optional separate Jolt spec for the response body. Falls back to the main spec if empty.'
              }
            >
              <textarea
                value={str('responseSpec', '[]')}
                onChange={(e) => set('responseSpec', e.target.value)}
                rows={8}
                spellCheck={false}
                className={`${monoInputCls} resize-y`}
                placeholder={'[\n  {\n    "operation": "shift",\n    "spec": { "result": "data" }\n  }\n]'}
              />
            </Field>
          )}
          {(str('phase', 'REQUEST') === 'RESPONSE' || str('phase', 'REQUEST') === 'BOTH') && (
            <Field
              label="Max Body Size (bytes)"
              hint="Maximum response body size to transform. Bodies exceeding this limit pass through unchanged. Default: 1 MB (1048576)."
            >
              <input
                type="number"
                min={0}
                value={Number(config.maxBodySize ?? 1048576)}
                onChange={(e) => set('maxBodySize', Number(e.target.value))}
                className={inputCls}
                placeholder="1048576"
              />
            </Field>
          )}
        </div>
      )

    // ── Validation ────────────────────────────────────────────────────────────
    case 'VALIDATE_JSON_SCHEMA':
      return (
        <div className="space-y-4">
          <Field label="JSON Schema" hint="JSON Schema specification (default: Draft-07)">
            <textarea
              value={str('schema', '{}')}
              onChange={(e) => set('schema', e.target.value)}
              rows={8}
              spellCheck={false}
              className={`${monoInputCls} resize-y`}
              placeholder={
                '{\n  "type": "object",\n  "required": ["id"],\n  "properties": { "id": { "type": "string" } }\n}'
              }
            />
          </Field>
          <Field label="Spec Version" hint="JSON Schema draft to use for validation">
            <Select
              value={str('specVersion', 'V7')}
              onChange={(v) => set('specVersion', v)}
              options={[
                { value: 'V7', label: 'Draft-07 (default)' },
                { value: 'V4', label: 'Draft-04' },
                { value: 'V6', label: 'Draft-06' },
                { value: 'V201909', label: 'Draft 2019-09' },
                { value: 'V202012', label: 'Draft 2020-12' },
              ]}
            />
          </Field>
        </div>
      )

    // ── Resilience ────────────────────────────────────────────────────────────
    case 'TIMEOUT':
      return (
        <div className="space-y-4">
          <Field
            label="Timeout (ms)"
            hint="Per-route request timeout in milliseconds. Returns 504 if the upstream does not respond in time. Default: 30000 (30 s)."
          >
            <input
              type="number"
              min={100}
              value={num('timeoutMs', 30000)}
              onChange={(e) => set('timeoutMs', +e.target.value)}
              className={inputCls}
            />
          </Field>
        </div>
      )

    // ── Observability ─────────────────────────────────────────────────────────
    case 'CORRELATION_ID':
      return (
        <div className="py-4 text-center">
          <p className="text-sm text-gray-500">
            Injects a unique <code className="font-mono text-indigo-400">X-Correlation-Id</code> into every request.
          </p>
          <p className="text-xs text-gray-600 mt-1 leading-relaxed max-w-xs mx-auto">
            Preserves an existing correlation ID if the header is already present. Propagates it to the response and MDC
            for log correlation. Runs at order −1000 (always first).
          </p>
          <p className="text-xs text-gray-600 mt-2">No configuration required.</p>
        </div>
      )

    case 'REQUEST_LOGGER': {
      const threshold = (config['failedStatusThreshold'] as number) ?? 500
      const currentSamplingRate = (config['samplingRate'] as number) ?? 1.0
      return (
        <div className="space-y-4">
          <SectionTitle>What to log</SectionTitle>
          <div className="rounded-xl border border-white/[0.07] bg-white/[0.02] grid grid-cols-1 sm:grid-cols-2 divide-y sm:divide-y-0 sm:divide-x divide-white/[0.05] overflow-hidden">
            <div className="divide-y divide-white/[0.05]">
              <Toggle
                label="Request Headers"
                description="Log incoming request header names and values"
                checked={bool('logRequestHeaders', true)}
                onChange={(v) => set('logRequestHeaders', v)}
              />
              <Toggle
                label="Response Headers"
                description="Log upstream response header names and values"
                checked={bool('logResponseHeaders', true)}
                onChange={(v) => set('logResponseHeaders', v)}
              />
            </div>
            <div className="divide-y divide-white/[0.05]">
              <Toggle
                label="Request Body"
                description="Capture and log the raw request body (up to max size)"
                checked={bool('logRequestBody')}
                onChange={(v) => set('logRequestBody', v)}
              />
              <Toggle
                label="Response Body"
                description="Capture and log the raw response body (up to max size)"
                checked={bool('logResponseBody')}
                onChange={(v) => set('logResponseBody', v)}
              />
            </div>
          </div>

          <SectionTitle>Body capture</SectionTitle>
          <div className="grid grid-cols-2 gap-3">
            <Field
              label="Max Body Capture (bytes)"
              hint="Max bytes captured from request/response body. Hard limit: 65536 (64 KB). Default: 4096."
            >
              <input
                type="number"
                min={0}
                max={65536}
                value={num('maxBodyCaptureBytes', 4096)}
                onChange={(e) => set('maxBodyCaptureBytes', Math.min(+e.target.value, 65536))}
                className={inputCls}
              />
            </Field>
            <Field label="Mark as failed when status ≥" hint="At or above this value requests are flagged for replay.">
              <div className="flex gap-2">
                {([400, 500] as const).map((v) => (
                  <button
                    key={v}
                    type="button"
                    onClick={() => set('failedStatusThreshold', v)}
                    className={cn(
                      'flex-1 py-2 rounded-lg border text-sm font-semibold transition-all',
                      threshold === v
                        ? v === 400
                          ? 'bg-amber-500/15 border-amber-500/40 text-amber-300'
                          : 'bg-red-500/15 border-red-500/40 text-red-300'
                        : 'bg-white/[0.03] border-white/[0.08] text-gray-500 hover:text-gray-300 hover:border-white/[0.15]',
                    )}
                  >
                    ≥ {v}
                    <span className="block text-[10px] font-normal mt-0.5 opacity-70">
                      {v === 400 ? '4xx + 5xx' : '5xx only'}
                    </span>
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-2 mt-2">
                <span className="text-[11px] text-gray-600">Custom:</span>
                <input
                  type="number"
                  min={100}
                  max={599}
                  value={threshold}
                  onChange={(e) => set('failedStatusThreshold', +e.target.value)}
                  className="w-20 bg-white/[0.04] border border-white/8 rounded-lg px-3 py-1.5 text-sm text-white font-mono focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all"
                />
              </div>
            </Field>
          </div>

          <SectionTitle>Sampling</SectionTitle>
          <Field
            label={`Sampling Rate: ${(currentSamplingRate * 100).toFixed(0)}%`}
            hint="Fraction of requests that generate telemetry events. 1.0 = all (default), 0.1 = ~10%, 0.0 = none."
          >
            <input
              type="range"
              min={0}
              max={1}
              step={0.01}
              value={currentSamplingRate}
              onChange={(e) => set('samplingRate', +e.target.value)}
              className="w-full accent-indigo-500 h-2 rounded-lg appearance-none cursor-pointer bg-white/10"
            />
            <div className="flex justify-between text-[10px] text-gray-600 mt-1">
              <span>0% (disabled)</span>
              <span>50%</span>
              <span>100% (all)</span>
            </div>
          </Field>

          <SectionTitle>Header capture</SectionTitle>
          <TagInput
            label="Header Allowlist"
            hint="When non-empty, only these headers are captured. Leave empty to capture all (except denied)."
            values={arr('headerAllowlist')}
            onChange={(v) => set('headerAllowlist', v)}
            placeholder="Content-Type"
            optional
          />
          <TagInput
            label="Header Denylist"
            hint="Headers to redact from telemetry. Deny overrides allow. Default: Authorization, Cookie, X-Api-Key."
            values={arr('headerDenylist')}
            onChange={(v) => set('headerDenylist', v)}
            placeholder="Authorization"
            optional
          />

          <SectionTitle>Path exclusions</SectionTitle>
          <TagInput
            label="Skip Paths"
            hint="Glob patterns for paths to exclude from logging and telemetry entirely. E.g. /actuator/**, /health"
            values={arr('skipPaths')}
            onChange={(v) => set('skipPaths', v)}
            placeholder="/actuator/**"
            optional
          />

          <div
            className="flex items-start gap-2 rounded-lg border px-3 py-2 text-[11px] leading-relaxed
            bg-amber-500/[0.06] border-amber-500/20 text-amber-400/80"
          >
            <span className="mt-0.5 shrink-0">⚠</span>
            <span>
              Setting threshold to <strong>400</strong> marks all client errors as failed and enqueues them for replay.
              Only use this if your upstream is authoritative for 4xx responses and retrying them makes sense.
            </span>
          </div>
          <p className="text-xs text-gray-500 bg-indigo-500/10 border border-indigo-500/20 rounded-lg px-3 py-2">
            Sensitive headers (<code className="font-mono text-indigo-300">Authorization</code>,{' '}
            <code className="font-mono text-indigo-300">X-Api-Key</code>,{' '}
            <code className="font-mono text-indigo-300">Cookie</code>) are redacted by default.
            Use <strong>Header Denylist</strong> to customise which headers are redacted.
            Structured MDC logging emits <code className="font-mono text-indigo-300">method</code>,{' '}
            <code className="font-mono text-indigo-300">path</code>,{' '}
            <code className="font-mono text-indigo-300">status</code>,{' '}
            <code className="font-mono text-indigo-300">elapsedMs</code>,{' '}
            <code className="font-mono text-indigo-300">correlationId</code> as MDC keys.
            Telemetry is published to the{' '}
            <code className="font-mono text-indigo-300">routify.request.telemetry</code> Kafka topic.
          </p>
        </div>
      )
    }

    case 'TENANT_CONTEXT':
      return (
        <div className="py-4 text-center">
          <p className="text-sm text-gray-500">
            Resolves and propagates tenant context through the gateway filter chain.
          </p>
          <p className="text-xs text-gray-600 mt-1 leading-relaxed max-w-sm mx-auto">
            When <strong className="text-indigo-400">enabled</strong>, callers supply{' '}
            <code className="font-mono text-indigo-400">X-Tenant-Id</code> (or it is derived from the JWT{' '}
            <code className="font-mono text-indigo-400">X-Auth-Tenant-Id</code> claim). Cross-validates the two values —
            mismatches are rejected with 403. When <strong className="text-indigo-400">disabled</strong>, the gateway
            auto-injects the tenant from route metadata.
          </p>
          <p className="text-xs text-gray-600 mt-1.5 leading-relaxed max-w-sm mx-auto">
            <strong className="text-indigo-400">Forward Header to Upstream</strong> controls whether{' '}
            <code className="font-mono text-indigo-400">X-Tenant-Id</code> is included in the final upstream request.
            When off, the header is stripped before leaving the gateway but remains available to other filters in the
            chain.
          </p>
          <p className="text-xs text-gray-600 mt-2">Configured via Gateway Settings → Tenant Isolation.</p>
        </div>
      )

    case 'CUSTOM_METRIC':
      return (
        <div className="space-y-4">
          <Field label="Metric Name" hint="Micrometer counter name, e.g. routify_custom_events_total">
            <input
              value={str('metricName')}
              onChange={(e) => set('metricName', e.target.value)}
              className={monoInputCls}
              placeholder="routify_custom_events_total"
            />
          </Field>
          <Field label="Description" hint="Optional metric description shown in /actuator/metrics" optional>
            <input
              value={str('description')}
              onChange={(e) => set('description', e.target.value)}
              className={inputCls}
              placeholder="My custom event counter"
            />
          </Field>
          <KeyValueFields
            label="Static Tags"
            hint='Micrometer tags. Use "$header.X-Tenant-Id" as a value for dynamic per-request tag values.'
            obj={(config.tags as Record<string, string>) ?? {}}
            onChange={(v) => set('tags', v)}
            optional
          />
        </div>
      )

    // ── Versioning / Security / Custom ────────────────────────────────────────
    case 'API_VERSIONING':
      return (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Version" hint="e.g. v2 or 2">
              <input
                value={str('version', 'v1')}
                onChange={(e) => set('version', e.target.value)}
                className={inputCls}
                placeholder="v1"
              />
            </Field>
            <Field label="Strategy">
              <Select
                value={str('strategy', 'HEADER')}
                onChange={(v) => set('strategy', v)}
                options={[
                  { value: 'HEADER', label: 'Header injection', description: 'Inject version via a request header' },
                  { value: 'QUERY', label: 'Query parameter', description: 'Inject version via a query param' },
                  { value: 'PATH', label: 'Path prefix rewrite', description: 'Rewrite path prefix with version' },
                ]}
              />
            </Field>
          </div>
          {str('strategy', 'HEADER') === 'HEADER' && (
            <Field label="Version Header" hint="Request header to inject the version into">
              <input
                value={str('versionHeader', 'X-Api-Version')}
                onChange={(e) => set('versionHeader', e.target.value)}
                className={inputCls}
                placeholder="X-Api-Version"
              />
            </Field>
          )}
          {str('strategy') === 'QUERY' && (
            <Field label="Query Param Name" hint="Query parameter to append the version to">
              <input
                value={str('versionParam', 'version')}
                onChange={(e) => set('versionParam', e.target.value)}
                className={inputCls}
                placeholder="version"
              />
            </Field>
          )}
          {str('strategy') === 'PATH' && (
            <Field
              label="Version Prefix"
              hint="Path prefix to prepend, e.g. /v2. Defaults to / + version if blank."
              optional
            >
              <input
                value={str('versionPrefix')}
                onChange={(e) => set('versionPrefix', e.target.value)}
                className={monoInputCls}
                placeholder="/v2"
              />
            </Field>
          )}
        </div>
      )

    case 'SECURITY_HEADERS':
      return (
        <div className="py-4 text-center">
          <p className="text-sm text-gray-500">Injects OWASP-recommended security response headers.</p>
          <p className="text-xs text-gray-600 mt-1 leading-relaxed max-w-sm mx-auto">
            Header values are pre-configured with OWASP-recommended defaults and enforced on every response by the{' '}
            <code className="font-mono text-indigo-400">GlobalSecurityHeadersFilter</code> — no per-filter or gateway
            configuration dependency.
          </p>
          <div className="mt-3 text-left inline-block space-y-1.5">
            {[
              'X-Content-Type-Options: nosniff',
              'X-Frame-Options: DENY',
              'X-XSS-Protection: 1; mode=block',
              'Strict-Transport-Security: max-age=31536000; includeSubDomains',
              'Referrer-Policy: strict-origin-when-cross-origin',
              'Permissions-Policy: geolocation=(), camera=(), microphone=()',
            ].map((h) => (
              <div
                key={h}
                className="text-[11px] font-mono text-indigo-300/80 bg-indigo-500/5 border border-indigo-500/10 rounded px-2 py-0.5"
              >
                {h}
              </div>
            ))}
          </div>
          <p className="text-xs text-gray-600 mt-3">
            Also removes <code className="font-mono text-indigo-400">Server</code> and{' '}
            <code className="font-mono text-indigo-400">X-Powered-By</code> headers.
          </p>
          <p className="text-xs text-gray-600 mt-1">No per-filter configuration required.</p>
        </div>
      )

    case 'CERT_ROTATION':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-blue-500/10 border border-blue-500/20 rounded-lg px-3 py-2">
            Validates that the client's certificate matches an <strong className="text-blue-300">active</strong> version
            in the Certificate Registry bound to the configured{' '}
            <strong className="text-blue-300">Certificate Group</strong>. Requests with revoked or unknown certificates
            are rejected with 401.
          </p>
          <Field
            label="Group Logical ID"
            hint="logicalId of the Certificate Group — must be registered in the gateway's Certificate Registry"
          >
            <input
              value={str('logicalId')}
              onChange={(e) => set('logicalId', e.target.value)}
              className={monoInputCls}
              placeholder="my-client-cert-group"
            />
          </Field>
          <Field
            label="Certificate Header"
            hint="Request header that carries the PEM-encoded client certificate"
            optional
          >
            <input
              value={str('certificateHeader', 'X-Client-Certificate')}
              onChange={(e) => set('certificateHeader', e.target.value)}
              className={inputCls}
              placeholder="X-Client-Certificate"
            />
          </Field>
        </div>
      )

    case 'CERT_VAULT_EXPIRY_CHECK':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-red-500/10 border border-red-500/20 rounded-lg px-3 py-2">
            Checks the lifecycle status of all active certificates within the bound
            <strong className="text-red-300"> Certificate Group </strong>
            on every request. Returns <code className="font-mono text-red-300">503 Service Unavailable</code> if all
            group members are expired or revoked. Optionally also blocks when approaching expiry within the configured
            warning window. Injects <code className="font-mono text-red-300">X-Cert-Status</code>,{' '}
            <code className="font-mono text-red-300">X-Cert-Expiry</code>,{' '}
            <code className="font-mono text-red-300">X-Cert-Days-Remaining</code>, and{' '}
            <code className="font-mono text-red-300">X-Cert-Fingerprint</code> headers downstream on success.
          </p>
          <Field
            label="Group Logical ID"
            hint="logicalId of the Certificate Group to check — must be registered in the gateway's Certificate Registry"
          >
            <input
              value={str('logicalId')}
              onChange={(e) => set('logicalId', e.target.value)}
              className={monoInputCls}
              placeholder="my-signing-cert-group"
            />
          </Field>
          <SectionTitle>Expiry Policy</SectionTitle>
          <Field
            label="Warning Window (days)"
            hint="Days before expiry at which the certificate is marked EXPIRING_SOON"
          >
            <input
              type="number"
              min={1}
              max={365}
              value={num('warningDays', 30)}
              onChange={(e) => set('warningDays', +e.target.value)}
              className={inputCls}
            />
          </Field>
          <Toggle
            label="Reject on Expiring Soon"
            description="Return 503 when the certificate enters the warning window. When off, only injects X-Cert-Status: EXPIRING_SOON header and lets the request through."
            checked={bool('rejectOnExpiringSoon', false)}
            onChange={(v) => set('rejectOnExpiringSoon', v)}
          />
          <Toggle
            label="Inject Metadata Headers"
            description="Forward X-Cert-Status, X-Cert-Expiry, X-Cert-Days-Remaining and X-Cert-Fingerprint to the upstream service"
            checked={bool('injectMetadataHeaders', true)}
            onChange={(v) => set('injectMetadataHeaders', v)}
          />
        </div>
      )

    case 'CUSTOM_SPEL':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-indigo-500/10 border border-indigo-500/20 rounded-lg px-3 py-2">
            Evaluated against a per-request context with variables
            <code className="font-mono text-indigo-300 mx-1">#request</code>,
            <code className="font-mono text-indigo-300 mx-1">#headers</code>,
            <code className="font-mono text-indigo-300 mx-1">#params</code>,
            <code className="font-mono text-indigo-300 mx-1">#method</code>,
            <code className="font-mono text-indigo-300 mx-1">#path</code>. Returning{' '}
            <code className="font-mono text-indigo-300">false</code> rejects the request with 403.
          </p>
          <Field label="SpEL Expression" hint="e.g. #headers['X-Feature-Flag'] == 'enabled'">
            <textarea
              value={str('expression')}
              onChange={(e) => set('expression', e.target.value)}
              rows={4}
              spellCheck={false}
              className={`${monoInputCls} resize-y`}
              placeholder="#headers['X-Feature-Flag'] == 'enabled'"
            />
          </Field>
          <Field label="Description" hint="Human-readable label logged at DEBUG level" optional>
            <input
              value={str('description')}
              onChange={(e) => set('description', e.target.value)}
              className={inputCls}
              placeholder="Block unauthenticated beta users"
            />
          </Field>
        </div>
      )

    case 'CONDITIONAL_ROUTE':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-pink-500/10 border border-pink-500/20 rounded-lg px-3 py-2">
            When the <strong className="text-pink-300">condition header</strong> or{' '}
            <strong className="text-pink-300">condition param</strong> matches the pattern, the upstream URI is
            rewritten to the <strong className="text-pink-300">alternative URI</strong>. Header takes priority over
            param.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Condition Header" hint="Request header to match" optional>
              <input
                value={str('conditionHeader')}
                onChange={(e) => set('conditionHeader', e.target.value)}
                className={inputCls}
                placeholder="X-Beta-User"
              />
            </Field>
            <Field label="Condition Param" hint="Query param to match" optional>
              <input
                value={str('conditionParam')}
                onChange={(e) => set('conditionParam', e.target.value)}
                className={inputCls}
                placeholder="beta"
              />
            </Field>
          </div>
          <Field label="Condition Pattern" hint="Java regex the header/param value must fully match">
            <input
              value={str('conditionPattern', '.*')}
              onChange={(e) => set('conditionPattern', e.target.value)}
              className={monoInputCls}
              placeholder="true|1|yes"
            />
          </Field>
          <Field label="Alternative URI" hint="Upstream URI to route to when condition matches">
            <input
              value={str('alternativeUri')}
              onChange={(e) => set('alternativeUri', e.target.value)}
              className={inputCls}
              placeholder="http://beta-service:8080"
            />
          </Field>
        </div>
      )

    // ── Downstream Auth ───────────────────────────────────────────────────────
    case 'DOWNSTREAM_BASIC_AUTH':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-violet-500/10 border border-violet-500/20 rounded-lg px-3 py-2">
            Injects a <strong className="text-violet-300">Basic Authorization</strong> header into every request
            forwarded to the upstream service.
          </p>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Username" optional>
              <input
                value={str('username')}
                onChange={(e) => set('username', e.target.value)}
                className={inputCls}
                placeholder="service-account"
                autoComplete="off"
              />
            </Field>
            <Field label="Password" optional>
              <input
                type="password"
                value={str('password')}
                onChange={(e) => set('password', e.target.value)}
                className={inputCls}
                placeholder="••••••••"
                autoComplete="new-password"
              />
            </Field>
          </div>
        </div>
      )

    case 'DOWNSTREAM_BEARER_CC':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-violet-500/10 border border-violet-500/20 rounded-lg px-3 py-2">
            Acquires an OAuth2 <strong className="text-violet-300">client-credentials</strong> token from the named
            provider and injects it as <code className="font-mono text-violet-300">Authorization: Bearer …</code>{' '}
            downstream.
          </p>
          <Field label="OAuth2 Provider Name" hint="Logical name of the OAuth2 client-credentials provider">
            <input
              value={str('oauth2ProviderName')}
              onChange={(e) => set('oauth2ProviderName', e.target.value)}
              className={inputCls}
              placeholder="my-cc-provider"
            />
          </Field>
          <Toggle
            label="Forward Caller Auth"
            description="Forward the caller's own Authorization header to the token endpoint (uncached) instead of using stored client credentials"
            checked={bool('forwardCallerAuth')}
            onChange={(v) => set('forwardCallerAuth', v)}
          />
        </div>
      )

    // ── Routing — User ID Payload ─────────────────────────────────────────────
    case 'USER_ID_PAYLOAD_ROUTING':
      return (
        <div className="space-y-4">
          <p className="text-xs text-gray-500 bg-pink-500/10 border border-pink-500/20 rounded-lg px-3 py-2">
            Reads the request body (requires a preceding{' '}
            <code className="font-mono text-pink-300">CacheRequestBody</code> filter) and rewrites the upstream URI when
            the <code className="font-mono text-pink-300">userIdField</code> value is in the allowlist.
          </p>
          <Toggle
            label="Enabled"
            description="Disable to turn off routing without removing the filter"
            checked={bool('enabled', true)}
            onChange={(v) => set('enabled', v)}
          />
          <div className="grid grid-cols-2 gap-3">
            <Field label="User ID Field" hint="JSON field in the request body">
              <input
                value={str('userIdField', 'userId')}
                onChange={(e) => set('userIdField', e.target.value)}
                className={inputCls}
                placeholder="userId"
              />
            </Field>
          </div>
          <TagInput
            label="Allowlist User IDs"
            hint="Requests from these user IDs are routed to the alternative URI"
            values={arr('allowlistUserIds')}
            onChange={(v) => set('allowlistUserIds', v)}
            placeholder="user-uuid-1, user-uuid-2…"
            optional
          />
          <Field label="Alternative URI" hint="Upstream URI to route allowlisted users to" optional>
            <input
              value={str('alternativeUri')}
              onChange={(e) => set('alternativeUri', e.target.value)}
              className={inputCls}
              placeholder="http://beta-upstream:8080"
            />
          </Field>
        </div>
      )

    // ── AI Filter ─────────────────────────────────────────────────────────────
    case 'AI_FILTER':
      return <AiFilterFields config={config} onChange={onChange} />

    // ── AI Modifier ───────────────────────────────────────────────────────────
    case 'AI_MODIFIER':
      return <AiModifierFields config={config} onChange={onChange} />

    default:
      return (
        <div className="py-3 px-4 rounded-xl bg-amber-500/5 border border-amber-500/20">
          <p className="text-xs text-amber-400">
            No dedicated form for <code className="font-mono">{filterType}</code> yet — configuration will be saved
            as-is.
          </p>
        </div>
      )
  }
}

// ─── mTLS Mapping Fields ──────────────────────────────────────────────────────
// Matches backend: MtlsAuthGatewayFilterFactory uses CertificateValuesConfig
// with a `values` list of { clientIdValue, clientIdRequestHeader,
//   clientCertificateRequestHeader, clientCertificateValue } entries.

interface MtlsMapping {
  clientIdRequestHeader: string
  clientIdValue: string
  clientCertificateRequestHeader: string
  clientCertificateValue: string
}

function MtlsMappingFields({ config, onChange }: { config: FilterConfig; onChange: (c: FilterConfig) => void }) {
  const values: MtlsMapping[] = (config.values as MtlsMapping[]) ?? []

  const add = () =>
    onChange({
      ...config,
      values: [
        ...values,
        {
          clientIdRequestHeader: 'X-Client-Id',
          clientIdValue: '',
          clientCertificateRequestHeader: 'X-Client-Certificate',
          clientCertificateValue: '',
        },
      ],
    })

  const update = (i: number, field: keyof MtlsMapping, val: string) => {
    const next = values.map((v, idx) => (idx === i ? { ...v, [field]: val } : v))
    onChange({ ...config, values: next })
  }

  const remove = (i: number) => onChange({ ...config, values: values.filter((_, idx) => idx !== i) })

  return (
    <div className="space-y-4">
      <p className="text-xs text-gray-500 bg-blue-500/10 border border-blue-500/20 rounded-lg px-3 py-2">
        Each mapping binds a <strong className="text-blue-300">client ID</strong> value (from a request header) to an
        expected <strong className="text-blue-300">client certificate</strong> (also from a header, PEM-encoded). The
        certificate is matched against an active version in the Certificate Registry. On success injects{' '}
        <code className="font-mono text-blue-300 mx-0.5">organization-common-name</code> downstream.
      </p>
      {values.length === 0 && (
        <div className="text-xs text-gray-600 py-2 px-3 bg-white/[0.02] rounded-lg border border-dashed border-white/10">
          No mappings yet — click "+ Add Mapping" to start
        </div>
      )}
      {values.map((v, i) => (
        <div key={i} className="rounded-lg border border-white/[0.08] bg-white/[0.02] p-3 space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-[11px] font-bold text-gray-500 uppercase tracking-widest">Mapping #{i + 1}</span>
            <button
              type="button"
              onClick={() => remove(i)}
              className="text-xs text-gray-600 hover:text-red-400 transition-colors"
            >
              ✕ Remove
            </button>
          </div>
          <div className="grid grid-cols-2 gap-2">
            <Field label="Client ID Header">
              <input
                value={v.clientIdRequestHeader}
                onChange={(e) => update(i, 'clientIdRequestHeader', e.target.value)}
                className={inputCls}
                placeholder="X-Client-Id"
              />
            </Field>
            <Field label="Expected Client ID Value">
              <input
                value={v.clientIdValue}
                onChange={(e) => update(i, 'clientIdValue', e.target.value)}
                className={inputCls}
                placeholder="my-client-001"
              />
            </Field>
            <Field label="Certificate Header">
              <input
                value={v.clientCertificateRequestHeader}
                onChange={(e) => update(i, 'clientCertificateRequestHeader', e.target.value)}
                className={inputCls}
                placeholder="X-Client-Certificate"
              />
            </Field>
            <Field label="Certificate Registry ID" hint="logicalId in the CertificateRegistry">
              <input
                value={v.clientCertificateValue}
                onChange={(e) => update(i, 'clientCertificateValue', e.target.value)}
                className={monoInputCls}
                placeholder="my-client-cert"
              />
            </Field>
          </div>
        </div>
      ))}
      <button type="button" onClick={add} className="text-xs text-indigo-400 hover:text-indigo-300 transition-colors">
        + Add Mapping
      </button>
    </div>
  )
}

// ─── Client ID Mapping Fields ─────────────────────────────────────────────────
// Matches backend: ClientIdAuthGatewayFilterFactory uses NameValuesConfig
// with a `values` list of AbstractNameValueGatewayFilterFactory.NameValueConfig
// entries: { name (header name), value (expected header value) }.

interface ClientIdEntry {
  name: string
  value: string
}

function ClientIdMappingFields({ config, onChange }: { config: FilterConfig; onChange: (c: FilterConfig) => void }) {
  const values: ClientIdEntry[] = (config.values as ClientIdEntry[]) ?? []

  const add = () => onChange({ ...config, values: [...values, { name: 'X-Client-Id', value: '' }] })

  const update = (i: number, field: keyof ClientIdEntry, val: string) => {
    const next = values.map((v, idx) => (idx === i ? { ...v, [field]: val } : v))
    onChange({ ...config, values: next })
  }

  const remove = (i: number) => onChange({ ...config, values: values.filter((_, idx) => idx !== i) })

  return (
    <div className="space-y-4">
      <p className="text-xs text-gray-500 bg-blue-500/10 border border-blue-500/20 rounded-lg px-3 py-2">
        Each entry maps a request <strong className="text-blue-300">header name</strong> to an expected
        <strong className="text-blue-300 ml-1">header value</strong>. When the incoming request matches any entry, the
        client is authenticated and <code className="font-mono text-blue-300 mx-0.5">organization-id</code> is resolved
        from the client ID mapping and injected downstream.
      </p>
      {values.length === 0 && (
        <div className="text-xs text-gray-600 py-2 px-3 bg-white/[0.02] rounded-lg border border-dashed border-white/10">
          No entries yet — click "+ Add Entry" to start
        </div>
      )}
      {values.map((v, i) => (
        <div key={i} className="flex items-center gap-2">
          <Field label={i === 0 ? 'Header Name' : ''}>
            <input
              value={v.name}
              onChange={(e) => update(i, 'name', e.target.value)}
              className={inputCls}
              placeholder="X-Client-Id"
            />
          </Field>
          <span className="text-gray-600 text-xs shrink-0 mt-1">=</span>
          <Field label={i === 0 ? 'Expected Value' : ''}>
            <input
              value={v.value}
              onChange={(e) => update(i, 'value', e.target.value)}
              className={monoInputCls}
              placeholder="client-abc-123"
            />
          </Field>
          <button
            type="button"
            onClick={() => remove(i)}
            className="p-1.5 text-gray-600 hover:text-red-400 transition-colors shrink-0 mt-1"
          >
            ×
          </button>
        </div>
      ))}
      <button type="button" onClick={add} className="text-xs text-indigo-400 hover:text-indigo-300 transition-colors">
        + Add Entry
      </button>
    </div>
  )
}

function HeaderModifyFields({
  label,
  config,
  onChange,
}: {
  label: string
  config: FilterConfig
  onChange: (c: FilterConfig) => void
}) {
  const add = (config.add as Record<string, string>) ?? {}
  const set_ = (config.set as Record<string, string>) ?? {}
  // Backend uses Map<String,String> for remove — keys are header names, values are ignored
  const removeMap = (config.remove as Record<string, string>) ?? {}
  const removeKeys = Object.keys(removeMap)

  const handleRemoveAdd = (headers: string[]) => {
    const next: Record<string, string> = {}
    headers.forEach((h) => {
      next[h] = ''
    })
    onChange({ ...config, remove: next })
  }

  return (
    <div className="space-y-5">
      <KeyValueFields
        label={`Add ${label} Headers`}
        hint="Headers appended to existing values"
        obj={add}
        onChange={(v) => onChange({ ...config, add: v })}
        optional
      />
      <KeyValueFields
        label={`Set ${label} Headers`}
        hint="Headers that overwrite existing values"
        obj={set_}
        onChange={(v) => onChange({ ...config, set: v })}
        optional
      />
      <div className="space-y-1.5">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Remove {label} Headers</span>
          <span className="text-[10px] text-gray-600">optional</span>
        </div>
        <TagInput
          label=""
          values={removeKeys}
          onChange={handleRemoveAdd}
          placeholder="X-Internal-Header…"
          hint="Header names to strip — press Enter to add each one"
        />
      </div>
    </div>
  )
}

function KeyValueFields({
  label,
  hint,
  obj,
  onChange,
  optional,
}: {
  label: string
  hint?: string
  obj: Record<string, string>
  onChange: (v: Record<string, string>) => void
  optional?: boolean
}) {
  // Use local array state so editing a key doesn't re-key the list on every keystroke
  const [rows, setRows] = useState<[string, string][]>(() => Object.entries(obj))

  // Sync inbound prop changes (e.g. reset when filter type changes) without clobbering
  // ongoing edits — compare by serialised content so that a new object reference
  // produced by the parent re-render after our own onChange call doesn't wipe the rows.
  // Uses setState-during-render (React-endorsed pattern for adjusting state on prop change).
  const [prevSerialized, setPrevSerialized] = useState(() => JSON.stringify(obj))
  const serialized = JSON.stringify(obj)
  if (prevSerialized !== serialized) {
    setPrevSerialized(serialized)
    setRows(Object.entries(obj))
  }

  const flush = (next: [string, string][]) => {
    setRows(next)
    const result: Record<string, string> = {}
    next.forEach(([k, v]) => {
      if (k) result[k] = v
    })
    onChange(result)
  }

  const updateKey = (idx: number, newKey: string) => {
    const next = rows.map((r, i) => (i === idx ? ([newKey, r[1]] as [string, string]) : r))
    flush(next)
  }

  const updateVal = (idx: number, newVal: string) => {
    const next = rows.map((r, i) => (i === idx ? ([r[0], newVal] as [string, string]) : r))
    flush(next)
  }

  const remove = (idx: number) => flush(rows.filter((_, i) => i !== idx))

  const add = () => flush([...rows, ['', '']])

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-gray-400 uppercase tracking-wider">{label}</span>
          {optional && <span className="text-[10px] text-gray-600">optional</span>}
        </div>
        <button type="button" onClick={add} className="text-xs text-indigo-400 hover:text-indigo-300 transition-colors">
          + Add
        </button>
      </div>
      {hint && <p className="text-[11px] text-gray-600">{hint}</p>}
      {rows.length === 0 ? (
        <div className="text-xs text-gray-600 py-2 px-3 bg-white/[0.02] rounded-lg border border-dashed border-white/10">
          No entries yet — click "+ Add" to start
        </div>
      ) : (
        <div className="space-y-2">
          {rows.map(([k, v], i) => (
            <div key={i} className="flex items-center gap-2">
              <input
                value={k}
                onChange={(e) => updateKey(i, e.target.value)}
                placeholder="Header-Name"
                className={cn(inputCls, 'flex-1 font-mono text-xs')}
              />
              <span className="text-gray-600 text-xs shrink-0">:</span>
              <input
                value={v}
                onChange={(e) => updateVal(i, e.target.value)}
                placeholder="value"
                className={cn(inputCls, 'flex-1 font-mono text-xs')}
              />
              <button
                type="button"
                onClick={() => remove(i)}
                className="p-1.5 text-gray-600 hover:text-red-400 transition-colors shrink-0"
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ─── AI Filter Fields ─────────────────────────────────────────────────────────

function AiFilterFields({ config, onChange }: { config: FilterConfig; onChange: (c: FilterConfig) => void }) {
  const set = (key: string, value: unknown) => onChange({ ...config, [key]: value })
  const str = (k: string, d = '') => (config[k] as string) ?? d
  const num = (k: string, d = 0) => (config[k] as number) ?? d
  const bool = (k: string, d = false) => (config[k] as boolean) ?? d

  const [testResult, setTestResult] = useState<{ action: string; reason: string; confidence: number } | null>(null)
  const [testLoading, setTestLoading] = useState(false)
  const [testError, setTestError] = useState<string | null>(null)
  const [sampleBody, setSampleBody] = useState('')
  const [samplePath, setSamplePath] = useState('/api/v1/test')

  const runTest = async () => {
    if (!str('policyDescription').trim()) return
    setTestLoading(true)
    setTestError(null)
    setTestResult(null)
    try {
      const result = await aiApi.testPolicy({
        policyDescription: str('policyDescription'),
        sampleRequest: { method: 'POST', path: samplePath, headers: {}, body: sampleBody || undefined },
      })
      setTestResult(result)
    } catch {
      setTestError('Test failed — check that the AI service is reachable')
    } finally {
      setTestLoading(false)
    }
  }

  return (
    <div className="space-y-5">
      <p className="text-xs text-gray-500 bg-fuchsia-500/10 border border-fuchsia-500/20 rounded-lg px-3 py-2">
        Evaluates each request against your natural-language policy using a local LLM. Verdicts:{' '}
        <span className="text-emerald-300 font-semibold">ALLOW</span> ·{' '}
        <span className="text-red-300 font-semibold">BLOCK</span> ·{' '}
        <span className="text-amber-300 font-semibold">FLAG</span>. Use{' '}
        <strong className="text-fuchsia-300">ASYNC mode</strong> first to monitor without blocking.
      </p>

      <Field label="Policy Description" hint="Natural-language rule for the LLM to enforce">
        <textarea
          value={str('policyDescription')}
          onChange={(e) => set('policyDescription', e.target.value)}
          rows={3}
          maxLength={2000}
          placeholder="Block requests that appear to contain SQL injection patterns"
          className={`${inputCls} resize-y`}
        />
        <p className="text-[10px] text-gray-600 text-right">{str('policyDescription').length}/2000</p>
      </Field>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Evaluation Mode">
          <Select
            value={str('evaluationMode', 'SYNC')}
            onChange={(v) => set('evaluationMode', v)}
            options={[
              { value: 'SYNC', label: 'SYNC — block until verdict' },
              { value: 'ASYNC', label: 'ASYNC — monitor only (non-blocking)' },
            ]}
          />
        </Field>
        <Field label="Fallback Action" hint="Applied when LLM is unavailable">
          <Select
            value={str('fallbackAction', 'ALLOW')}
            onChange={(v) => set('fallbackAction', v)}
            options={[
              { value: 'ALLOW', label: 'ALLOW (fail-open)' },
              { value: 'BLOCK', label: 'BLOCK (fail-closed)' },
            ]}
          />
        </Field>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Confidence Threshold" hint="Min LLM confidence (0.0–1.0)">
          <input
            type="number"
            min={0}
            max={1}
            step={0.05}
            value={num('confidenceThreshold', 0.85)}
            onChange={(e) => set('confidenceThreshold', parseFloat(e.target.value))}
            className={inputCls}
          />
        </Field>
        <Field label="Cache TTL (s)">
          <input
            type="number"
            min={0}
            value={num('cacheTtlSeconds', 30)}
            onChange={(e) => set('cacheTtlSeconds', parseInt(e.target.value))}
            className={inputCls}
          />
        </Field>
      </div>

      <SectionTitle>Body Analysis</SectionTitle>
      <div className="grid grid-cols-2 divide-x divide-white/[0.04] rounded-xl border border-white/[0.07] bg-white/[0.02] overflow-hidden">
        <Toggle
          label="Include Body in Prompt"
          description="Send a body excerpt to the LLM"
          checked={bool('includeBody')}
          onChange={(v) => set('includeBody', v)}
        />
        <Toggle
          label="Enable Verdict Cache"
          description="Serve identical requests from Redis"
          checked={bool('cacheEnabled', true)}
          onChange={(v) => set('cacheEnabled', v)}
        />
      </div>
      {bool('includeBody') && (
        <Field label="Max Body Bytes">
          <input
            type="number"
            min={64}
            max={8192}
            value={num('maxBodyBytes', 512)}
            onChange={(e) => set('maxBodyBytes', parseInt(e.target.value))}
            className={inputCls}
          />
        </Field>
      )}

      <SectionTitle>Test Policy (Dry Run)</SectionTitle>
      <div className="space-y-3 rounded-xl bg-white/[0.02] border border-white/[0.05] p-4">
        <p className="text-xs text-gray-500">Test against a sample before enabling on live traffic.</p>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Sample Path" optional>
            <input
              value={samplePath}
              onChange={(e) => setSamplePath(e.target.value)}
              className={inputCls}
              placeholder="/api/v1/users?id=1 OR 1=1"
            />
          </Field>
          <Field label="Sample Body" optional>
            <textarea
              value={sampleBody}
              onChange={(e) => setSampleBody(e.target.value)}
              rows={1}
              placeholder='{"query":"SELECT * FROM users"}'
              className={`${monoInputCls} resize-none`}
            />
          </Field>
        </div>
        <button
          type="button"
          onClick={runTest}
          disabled={testLoading || !str('policyDescription').trim()}
          className="px-3 py-1.5 text-xs font-semibold bg-fuchsia-600 hover:bg-fuchsia-500 disabled:opacity-50 text-white rounded-lg transition-all"
        >
          {testLoading ? 'Running…' : '▶ Run Test'}
        </button>
        {testError && <p className="text-xs text-red-400">{testError}</p>}
        {testResult && (
          <div
            className={cn(
              'px-3 py-2.5 rounded-lg border text-xs space-y-1',
              testResult.action === 'BLOCK'
                ? 'bg-red-500/10 border-red-500/30 text-red-300'
                : testResult.action === 'FLAG'
                  ? 'bg-amber-500/10 border-amber-500/30 text-amber-300'
                  : 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300',
            )}
          >
            <div className="font-bold text-sm">{testResult.action}</div>
            <div className="text-gray-400">{testResult.reason}</div>
            <div className="text-gray-600">Confidence: {(testResult.confidence * 100).toFixed(0)}%</div>
          </div>
        )}
      </div>
    </div>
  )
}

// ─── AI Modifier Fields ───────────────────────────────────────────────────────

function AiModifierFields({ config, onChange }: { config: FilterConfig; onChange: (c: FilterConfig) => void }) {
  const set = (key: string, value: unknown) => onChange({ ...config, [key]: value })
  const str = (k: string, d = '') => (config[k] as string) ?? d
  const num = (k: string, d = 0) => (config[k] as number) ?? d
  const bool = (k: string, d = false) => (config[k] as boolean) ?? d

  const [testResult, setTestResult] = useState<AiModificationTestResult | null>(null)
  const [testLoading, setTestLoading] = useState(false)
  const [testError, setTestError] = useState<string | null>(null)
  const [sampleBody, setSampleBody] = useState('')
  const [samplePath, setSamplePath] = useState('/api/v1/test')

  const runTest = async () => {
    if (!str('modificationPrompt').trim()) return
    setTestLoading(true)
    setTestError(null)
    setTestResult(null)
    try {
      const req: AiModificationTestRequest = {
        modificationPrompt: str('modificationPrompt'),
        targetFields: str('targetFields', 'BODY'),
        sampleRequest: { method: 'POST', path: samplePath, headers: {}, body: sampleBody || undefined },
      }
      setTestResult(await aiApi.testModification(req))
    } catch {
      setTestError('Test failed — check that the AI service is reachable')
    } finally {
      setTestLoading(false)
    }
  }

  const mutationBadgeClass = (t?: string) => {
    switch (t) {
      case 'PII_SCRUB':
        return 'text-rose-300 bg-rose-500/10 border-rose-500/20'
      case 'TRANSLATE':
        return 'text-blue-300 bg-blue-500/10 border-blue-500/20'
      case 'HEADER_REWRITE':
        return 'text-amber-300 bg-amber-500/10 border-amber-500/20'
      case 'PASSTHROUGH':
        return 'text-gray-400 bg-gray-500/10 border-gray-500/20'
      default:
        return 'text-fuchsia-300 bg-fuchsia-500/10 border-fuchsia-500/20'
    }
  }

  return (
    <div className="space-y-5">
      <p className="text-xs text-gray-500 bg-fuchsia-500/10 border border-fuchsia-500/20 rounded-lg px-3 py-2">
        The AI modifier uses a local LLM to <strong className="text-fuchsia-300">mutate the request</strong> before
        routing downstream — PII scrubbing, payload translation, header rewriting. Only SHA-256 body hashes are audited;
        raw bodies are never logged.
      </p>

      <Field label="Modification Prompt" hint="Natural-language instruction for the LLM">
        <textarea
          value={str('modificationPrompt')}
          onChange={(e) => set('modificationPrompt', e.target.value)}
          rows={4}
          maxLength={2000}
          placeholder="Scrub all email addresses from the JSON body and replace with [REDACTED_EMAIL]"
          className={`${inputCls} resize-y`}
        />
        <div
          className={cn(
            'text-[10px] text-right',
            str('modificationPrompt').length > 1800 ? 'text-amber-400' : 'text-gray-600',
          )}
        >
          {str('modificationPrompt').length}/2000
        </div>
      </Field>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Target Fields">
          <Select
            value={str('targetFields', 'BODY')}
            onChange={(v) => set('targetFields', v)}
            options={[
              { value: 'BODY', label: 'Body only' },
              { value: 'HEADERS', label: 'Headers only' },
              { value: 'BODY,HEADERS', label: 'Body + Headers' },
            ]}
          />
        </Field>
        <Field label="Fallback Behavior">
          <Select
            value={str('fallbackBehavior', 'PASSTHROUGH')}
            onChange={(v) => set('fallbackBehavior', v)}
            options={[
              { value: 'PASSTHROUGH', label: 'Pass Through (safe default)' },
              { value: 'BLOCK', label: 'Block (503)' },
            ]}
          />
        </Field>
      </div>

      <SectionTitle>LLM Parameters</SectionTitle>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Temperature" hint="0.0 = deterministic, 1.0 = creative">
          <input
            type="number"
            min={0}
            max={1}
            step={0.05}
            value={num('temperature', 0.1)}
            onChange={(e) => set('temperature', parseFloat(e.target.value))}
            className={inputCls}
          />
        </Field>
        <Field label="Max Tokens">
          <input
            type="number"
            min={256}
            max={4096}
            value={num('maxTokens', 1024)}
            onChange={(e) => set('maxTokens', parseInt(e.target.value))}
            className={inputCls}
          />
        </Field>
        <Field label="Timeout (ms)">
          <input
            type="number"
            min={500}
            value={num('timeoutMs', 4000)}
            onChange={(e) => set('timeoutMs', parseInt(e.target.value))}
            className={inputCls}
          />
        </Field>
        <Field label="Max Body Bytes">
          <input
            type="number"
            min={64}
            max={65536}
            value={num('maxBodyBytes', 2048)}
            onChange={(e) => set('maxBodyBytes', parseInt(e.target.value))}
            className={inputCls}
          />
        </Field>
      </div>
      <Field label="Model ID" hint="Leave blank for service default" optional>
        <input
          value={str('modelId')}
          onChange={(e) => set('modelId', e.target.value)}
          className={inputCls}
          placeholder="llama3:latest"
        />
      </Field>

      <SectionTitle>Caching</SectionTitle>
      <div className="rounded-xl border border-white/[0.07] bg-white/[0.02] overflow-hidden">
        <Toggle
          label="Enable Mutation Cache"
          description="Cache mutations in Redis. Recommended OFF unless mutations are request-fingerprint invariant"
          checked={bool('cacheEnabled')}
          onChange={(v) => set('cacheEnabled', v)}
        />
      </div>
      {bool('cacheEnabled') && (
        <div className="space-y-2">
          <div className="px-3 py-2 rounded-lg bg-amber-500/5 border border-amber-500/20 text-xs text-amber-300/80">
            ⚠ Caching mutations may produce incorrect results when body content varies between requests.
          </div>
          <Field label="Cache TTL (s)">
            <input
              type="number"
              min={1}
              value={num('cacheTtlSeconds', 60)}
              onChange={(e) => set('cacheTtlSeconds', parseInt(e.target.value))}
              className={inputCls}
            />
          </Field>
        </div>
      )}

      <SectionTitle>Test Modification (Dry Run)</SectionTitle>
      <div className="space-y-3 rounded-xl bg-white/[0.02] border border-white/[0.05] p-4">
        <p className="text-xs text-gray-500">Validate your prompt against a sample before enabling on live traffic.</p>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Sample Path" optional>
            <input
              value={samplePath}
              onChange={(e) => setSamplePath(e.target.value)}
              className={inputCls}
              placeholder="/api/v1/orders"
            />
          </Field>
          <Field label="Sample Body (JSON)" optional>
            <textarea
              value={sampleBody}
              onChange={(e) => setSampleBody(e.target.value)}
              rows={1}
              placeholder='{"email":"user@example.com","ssn":"123-45-6789","amount":100}'
              className={`${monoInputCls} resize-none`}
            />
          </Field>
        </div>
        <button
          type="button"
          onClick={runTest}
          disabled={testLoading || !str('modificationPrompt').trim()}
          className="px-3 py-1.5 text-xs font-semibold bg-fuchsia-600 hover:bg-fuchsia-500 disabled:opacity-50 text-white rounded-lg transition-all"
        >
          {testLoading ? 'Running…' : '▶ Run Modification Test'}
        </button>
        {testError && <p className="text-xs text-red-400">{testError}</p>}

        {testResult && (
          <div className="space-y-3 mt-2">
            <div className="flex items-center gap-2">
              <span
                className={cn(
                  'text-xs font-bold px-2 py-0.5 rounded-full border',
                  mutationBadgeClass(testResult.mutationType),
                )}
              >
                {testResult.mutationApplied ? `✓ ${testResult.mutationType}` : '⊘ PASSTHROUGH'}
              </span>
              <span className="text-xs text-gray-500">
                {testResult.latencyMs}ms{testResult.cached ? ' (cached)' : ''}
              </span>
            </div>
            <p className="text-xs text-gray-400 italic">{testResult.reason}</p>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1">
                <div className="text-[10px] font-bold text-gray-600 uppercase tracking-wider">Original</div>
                <pre className="text-xs text-gray-400 bg-white/[0.02] border border-white/[0.06] rounded-lg p-3 overflow-auto max-h-48 font-mono whitespace-pre-wrap">
                  {sampleBody || '(no body)'}
                </pre>
              </div>
              <div className="space-y-1">
                <div className="text-[10px] font-bold text-fuchsia-500 uppercase tracking-wider">Mutated</div>
                <pre
                  className={cn(
                    'text-xs bg-white/[0.02] border rounded-lg p-3 overflow-auto max-h-48 font-mono whitespace-pre-wrap',
                    testResult.mutatedBody
                      ? 'text-fuchsia-300 border-fuchsia-500/20'
                      : 'text-gray-600 border-white/[0.06]',
                  )}
                >
                  {testResult.mutatedBody ?? '(no mutation — same as original)'}
                </pre>
              </div>
            </div>
            {testResult.mutatedHeaders && Object.keys(testResult.mutatedHeaders).length > 0 && (
              <div className="space-y-1">
                <div className="text-[10px] font-bold text-fuchsia-500 uppercase tracking-wider">Mutated Headers</div>
                <div className="text-xs bg-white/[0.02] border border-fuchsia-500/20 rounded-lg p-3 space-y-1 font-mono">
                  {Object.entries(testResult.mutatedHeaders).map(([k, v]) => (
                    <div key={k} className="flex gap-2">
                      <span className="text-fuchsia-300">{k}:</span>
                      <span className="text-gray-300">{v}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
