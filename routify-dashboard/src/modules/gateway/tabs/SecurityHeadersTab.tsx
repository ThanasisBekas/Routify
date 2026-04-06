/**
 * SecurityHeadersTab — OWASP response-header configuration.
 *
 * Redesigned with icon-labelled sub-sections (Content & Frame,
 * Transport, Privacy, Fingerprinting) using the SubSection divider.
 */
import { useState } from 'react'
import { Shield, Frame, Lock, Eye, Cpu } from 'lucide-react'
import type { GatewaySecurityHeadersConfig } from '../../../types'
import {
  SectionHeader,
  SubSection,
  ToggleRow,
  Field,
  SaveBar,
  inputCls,
  monoInputCls,
  InfoBanner,
} from '../components/GatewayPrimitives'
import { Select } from '../../../components/ui/Select'

interface Props {
  initial: GatewaySecurityHeadersConfig
  onSave: (v: GatewaySecurityHeadersConfig) => void
  isPending: boolean
}

export default function SecurityHeadersTab({ initial, onSave, isPending }: Props) {
  const [cfg, setCfg] = useState<GatewaySecurityHeadersConfig>(initial)
  const dirty = JSON.stringify(cfg) !== JSON.stringify(initial)

  return (
    <div className="max-w-2xl">
      <SectionHeader
        icon={Shield}
        title="Security Response Headers"
        description="OWASP-recommended headers injected into every response by the gateway. These are independent of filter definitions — no per-route configuration needed."
      />

      {/* Master toggle callout */}
      <div
        className={`rounded-xl border px-4 py-4 mb-6 transition-colors ${
          cfg.enabled ? 'bg-emerald-500/5 border-emerald-500/20' : 'bg-amber-500/5 border-amber-500/20'
        }`}
      >
        <ToggleRow
          label="Enable Security Headers"
          description={
            cfg.enabled
              ? 'All configured headers below are injected on every response'
              : '⚠ Disabled — no security headers are injected; browser-side protections are removed'
          }
          checked={cfg.enabled}
          onChange={(v) => setCfg((p) => ({ ...p, enabled: v }))}
        />
      </div>

      <div className={cfg.enabled ? '' : 'opacity-50 pointer-events-none'}>
        {/* ── Content & Frame ────────────────────────────────────────────────── */}
        <SubSection label="Content & Frame Protection" icon={Frame} />
        <div className="space-y-1 mb-1">
          <ToggleRow
            label="X-Content-Type-Options: nosniff"
            description="Prevents MIME-type sniffing attacks (CVE-2016-1234 class)"
            checked={cfg.xContentTypeOptions}
            onChange={(v) => setCfg((p) => ({ ...p, xContentTypeOptions: v }))}
          />
          <ToggleRow
            label="X-Frame-Options"
            description="Prevents clickjacking via iframe embedding"
            checked={cfg.xFrameOptions}
            onChange={(v) => setCfg((p) => ({ ...p, xFrameOptions: v }))}
          />
          {cfg.xFrameOptions && (
            <div className="ml-4 pl-3 border-l border-white/[0.06] py-2">
              <Field label="X-Frame-Options value">
                <Select
                  value={cfg.xFrameOptionsValue}
                  onChange={(v) => setCfg((p) => ({ ...p, xFrameOptionsValue: v as 'DENY' | 'SAMEORIGIN' }))}
                  options={[
                    { value: 'DENY', label: 'DENY', description: 'Recommended — blocks all iframe embedding' },
                    {
                      value: 'SAMEORIGIN',
                      label: 'SAMEORIGIN',
                      description: 'Allows same-origin iframe embedding only',
                    },
                  ]}
                />
              </Field>
            </div>
          )}
          <ToggleRow
            label="X-XSS-Protection: 1; mode=block"
            description="Legacy XSS filter hint for older browsers (IE, pre-Chromium Edge)"
            checked={cfg.xXssProtection}
            onChange={(v) => setCfg((p) => ({ ...p, xXssProtection: v }))}
          />
        </div>

        {/* ── Transport Security ─────────────────────────────────────────────── */}
        <SubSection label="Transport Security" icon={Lock} />
        <div className="space-y-1 mb-1">
          <ToggleRow
            label="Strict-Transport-Security (HSTS)"
            description="Force HTTPS for this domain and optionally all subdomains"
            checked={cfg.strictTransportSecurity}
            onChange={(v) => setCfg((p) => ({ ...p, strictTransportSecurity: v }))}
          />
          {cfg.strictTransportSecurity && (
            <div className="ml-4 pl-3 border-l border-white/[0.06] py-2">
              <div className="grid grid-cols-3 gap-3">
                <Field label="Max Age (seconds)" hint="31536000 = 1 year">
                  <input
                    type="number"
                    value={cfg.stsMaxAge}
                    onChange={(e) => setCfg((p) => ({ ...p, stsMaxAge: Number(e.target.value) }))}
                    className={inputCls}
                  />
                </Field>
                <div className="flex items-end pb-1">
                  <ToggleRow
                    label="includeSubDomains"
                    checked={cfg.stsIncludeSubDomains}
                    onChange={(v) => setCfg((p) => ({ ...p, stsIncludeSubDomains: v }))}
                  />
                </div>
                <div className="flex items-end pb-1">
                  <ToggleRow
                    label="Preload"
                    checked={cfg.stsPreload}
                    onChange={(v) => setCfg((p) => ({ ...p, stsPreload: v }))}
                  />
                </div>
              </div>
              {cfg.stsPreload && (
                <InfoBanner variant="warning">
                  <strong>Preload</strong> submits your domain to browsers' built-in HSTS preload lists. This is
                  difficult to reverse — ensure HTTPS is correctly configured on all subdomains first.
                </InfoBanner>
              )}
            </div>
          )}
        </div>

        {/* ── Privacy & Permissions ──────────────────────────────────────────── */}
        <SubSection label="Privacy & Permissions" icon={Eye} />
        <div className="space-y-4 mb-1">
          <Field label="Referrer-Policy" hint="Controls how much referrer information is included with requests">
            <Select
              value={cfg.referrerPolicy}
              onChange={(v) => setCfg((p) => ({ ...p, referrerPolicy: v }))}
              searchable
              options={[
                'no-referrer',
                'no-referrer-when-downgrade',
                'origin',
                'origin-when-cross-origin',
                'same-origin',
                'strict-origin',
                'strict-origin-when-cross-origin',
                'unsafe-url',
              ].map((v) => ({ value: v, label: v }))}
            />
          </Field>
          <Field label="Permissions-Policy" hint="Restrict browser feature access, e.g. geolocation=(), camera=()">
            <input
              value={cfg.permissionsPolicy}
              onChange={(e) => setCfg((p) => ({ ...p, permissionsPolicy: e.target.value }))}
              className={monoInputCls}
              placeholder="geolocation=(), camera=(), microphone=()"
            />
          </Field>
          <Field
            label="Content-Security-Policy"
            optional
            hint="⚠ Strict CSP may break client applications — test thoroughly before enabling in production"
          >
            <input
              value={cfg.contentSecurityPolicy ?? ''}
              onChange={(e) => setCfg((p) => ({ ...p, contentSecurityPolicy: e.target.value || undefined }))}
              placeholder="default-src 'self'; script-src 'self'"
              className={monoInputCls}
            />
          </Field>
        </div>

        {/* ── Server Fingerprinting ──────────────────────────────────────────── */}
        <SubSection label="Server Fingerprinting" icon={Cpu} />
        <div className="space-y-1">
          <ToggleRow
            label="Remove Server header"
            description="Removes the upstream server identification header (e.g. nginx/1.23.4)"
            checked={cfg.removeServerHeader}
            onChange={(v) => setCfg((p) => ({ ...p, removeServerHeader: v }))}
          />
          <ToggleRow
            label="Remove X-Powered-By header"
            description="Removes framework / technology disclosure header"
            checked={cfg.removePoweredByHeader}
            onChange={(v) => setCfg((p) => ({ ...p, removePoweredByHeader: v }))}
          />
        </div>
      </div>

      <SaveBar onSave={() => onSave(cfg)} isPending={isPending} dirty={dirty} />
    </div>
  )
}
