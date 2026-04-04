/**
 * AuthProvidersTab — Auth provider CRUD.
 *
 * Redesigned with provider-type color chips, improved secret masking UX,
 * an extracted modal, and an empty state.
 */
import { useState } from 'react'
import { Lock, Plus, Edit, Trash2, X, Eye, EyeOff, Key } from 'lucide-react'
import type { GatewayAuthProvider } from '../../../types'
import {
  SectionHeader, ToggleRow, Field, EmptyState,
  inputCls, monoInputCls,
} from '../components/GatewayPrimitives'
import { Select } from '../../../components/ui/Select'

// ─── Provider type styles ─────────────────────────────────────────────────────

const TYPE_LABELS: Record<string, string> = {
  JWT_VERIFY:              'JWT (JWKS)',
  OAUTH2_CLIENT_CREDENTIALS: 'OAuth2 CC',
  OAUTH2_PASSWORD:           'OAuth2 Password',
  OAUTH2_INTROSPECT:         'OAuth2 Introspect',
  BASIC:                     'Basic Auth',
}

const TYPE_COLORS: Record<string, string> = {
  JWT_VERIFY:              'text-indigo-300 bg-indigo-500/10 border-indigo-500/20',
  OAUTH2_CLIENT_CREDENTIALS: 'text-teal-300 bg-teal-500/10 border-teal-500/20',
  OAUTH2_PASSWORD:           'text-violet-300 bg-violet-500/10 border-violet-500/20',
  OAUTH2_INTROSPECT:         'text-amber-300 bg-amber-500/10 border-amber-500/20',
  BASIC:                     'text-sky-300 bg-sky-500/10 border-sky-500/20',
}

/** Sentinel for masked secrets */
const SECRET_MASK = '••••••••'

function stripMaskedSecrets(p: GatewayAuthProvider): GatewayAuthProvider {
  return {
    ...p,
    clientSecret: p.clientSecret === SECRET_MASK ? undefined : p.clientSecret,
    password:     p.password     === SECRET_MASK ? undefined : p.password,
  }
}

// ─── Provider modal ───────────────────────────────────────────────────────────

const PROVIDER_TYPES = [
  { value: 'JWT_VERIFY',              label: 'JWT Verification (JWKS)',       description: 'Verify RS256/HS256 tokens against a JWKS endpoint' },
  { value: 'OAUTH2_CLIENT_CREDENTIALS', label: 'OAuth2 Client Credentials',   description: 'Machine-to-machine token acquisition (M2M)' },
  { value: 'OAUTH2_PASSWORD',          label: 'OAuth2 Password Grant',        description: 'Token acquisition with resource-owner credentials' },
  { value: 'OAUTH2_INTROSPECT',        label: 'OAuth2 Token Introspection',   description: 'Delegate validation to an authorization server' },
  { value: 'BASIC',                    label: 'Basic Auth',                   description: 'HTTP Basic username / password credentials' },
]

function ProviderModal({
  provider,
  isNew,
  isPending,
  onSave,
  onClose,
}: {
  provider: GatewayAuthProvider
  isNew: boolean
  isPending: boolean
  onSave: (p: GatewayAuthProvider) => void
  onClose: () => void
}) {
  const [p, setP] = useState(provider)
  const [showSecret, setShowSecret] = useState(false)
  const [showPassword, setShowPassword] = useState(false)

  const isOAuth2 = ['OAUTH2_CLIENT_CREDENTIALS', 'OAUTH2_PASSWORD', 'OAUTH2_INTROSPECT'].includes(p.type)

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-[#13151a] border border-white/10 rounded-xl w-full max-w-lg shadow-2xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.07] shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-7 h-7 rounded-lg bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center">
              <Lock className="w-3.5 h-3.5 text-indigo-400" />
            </div>
            <h3 className="text-sm font-semibold text-white">
              {isNew ? 'Add Auth Provider' : 'Edit Auth Provider'}
            </h3>
          </div>
          <button onClick={onClose} className="p-1.5 text-gray-400 hover:text-white hover:bg-white/[0.05] rounded-lg transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div className="col-span-2">
              <Field label="Provider Name">
                <input
                  value={p.name}
                  onChange={e => setP(prev => ({ ...prev, name: e.target.value }))}
                  placeholder="e.g. auth0-prod"
                  className={inputCls}
                />
              </Field>
            </div>
            <div className="col-span-2">
              <Field label="Type">
                <Select
                  value={p.type}
                  onChange={v => setP(prev => ({ ...prev, type: v as GatewayAuthProvider['type'] }))}
                  options={PROVIDER_TYPES}
                />
              </Field>
            </div>
          </div>

          {/* JWT */}
          {p.type === 'JWT_VERIFY' && (
            <>
              <Field label="JWKS URI">
                <input
                  value={p.jwksUri ?? ''}
                  onChange={e => setP(prev => ({ ...prev, jwksUri: e.target.value }))}
                  placeholder="https://auth.example.com/.well-known/jwks.json"
                  className={monoInputCls}
                />
              </Field>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Expected Issuer" optional>
                  <input value={p.issuer ?? ''} onChange={e => setP(prev => ({ ...prev, issuer: e.target.value }))} className={inputCls} placeholder="https://auth.example.com" />
                </Field>
                <Field label="Expected Audience" optional>
                  <input value={p.audience ?? ''} onChange={e => setP(prev => ({ ...prev, audience: e.target.value }))} className={inputCls} placeholder="api://my-app" />
                </Field>
              </div>
              <Field label="Algorithm">
                <Select
                  value={p.algorithm ?? 'RS256'}
                  onChange={v => setP(prev => ({ ...prev, algorithm: v }))}
                  options={['RS256', 'RS384', 'RS512', 'HS256', 'ES256'].map(a => ({ value: a, label: a }))}
                />
              </Field>
            </>
          )}

          {/* OAuth2 shared fields */}
          {isOAuth2 && (
            <>
              <Field label={p.type === 'OAUTH2_INTROSPECT' ? 'Introspection Endpoint' : 'Token Endpoint'}>
                <input
                  value={p.uri ?? ''}
                  onChange={e => setP(prev => ({ ...prev, uri: e.target.value }))}
                  placeholder={p.type === 'OAUTH2_INTROSPECT' ? 'https://auth.example.com/oauth/introspect' : 'https://auth.example.com/oauth/token'}
                  className={monoInputCls}
                />
              </Field>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Client ID">
                  <input value={p.clientId ?? ''} onChange={e => setP(prev => ({ ...prev, clientId: e.target.value }))} className={inputCls} />
                </Field>
                <Field
                  label="Client Secret"
                  hint={p.clientSecret === SECRET_MASK ? 'Currently stored — leave blank to keep unchanged' : undefined}
                >
                  <div className="relative">
                    <input
                      type={showSecret ? 'text' : 'password'}
                      value={p.clientSecret ?? ''}
                      placeholder={p.clientSecret === SECRET_MASK ? '(unchanged)' : ''}
                      onChange={e => setP(prev => ({ ...prev, clientSecret: e.target.value }))}
                      className={`${inputCls} pr-9`}
                    />
                    <button
                      type="button"
                      onClick={() => setShowSecret(s => !s)}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-white transition-colors"
                    >
                      {showSecret ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                    </button>
                  </div>
                </Field>
              </div>
              {p.type !== 'OAUTH2_INTROSPECT' && (
                <Field label="Scope" optional>
                  <input value={p.scope ?? ''} onChange={e => setP(prev => ({ ...prev, scope: e.target.value }))} placeholder="read write" className={inputCls} />
                </Field>
              )}
            </>
          )}

          {/* OAuth2 Password Grant: resource-owner creds */}
          {p.type === 'OAUTH2_PASSWORD' && (
            <>
              <div className="pt-1 text-xs font-semibold text-gray-500 uppercase tracking-widest">Resource-Owner Credentials</div>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Username">
                  <input value={p.username ?? ''} onChange={e => setP(prev => ({ ...prev, username: e.target.value }))} className={inputCls} />
                </Field>
                <Field label="Password" hint={p.password === SECRET_MASK ? 'Currently stored' : undefined}>
                  <div className="relative">
                    <input
                      type={showPassword ? 'text' : 'password'}
                      value={p.password ?? ''}
                      placeholder={p.password === SECRET_MASK ? '(unchanged)' : ''}
                      onChange={e => setP(prev => ({ ...prev, password: e.target.value }))}
                      className={`${inputCls} pr-9`}
                    />
                    <button type="button" onClick={() => setShowPassword(s => !s)} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-white">
                      {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                    </button>
                  </div>
                </Field>
              </div>
            </>
          )}

          {/* OAuth2 Introspect: token parameter style */}
          {p.type === 'OAUTH2_INTROSPECT' && (
            <div className="grid grid-cols-2 gap-4">
              <Field label="Parameter Style" hint="How the token is sent to the endpoint">
                <Select
                  value={p.parameterStyle ?? 'BODY'}
                  onChange={v => setP(prev => ({ ...prev, parameterStyle: v as 'BODY' | 'HEADER' }))}
                  options={[
                    { value: 'BODY',   label: 'BODY',   description: 'token= form field in request body' },
                    { value: 'HEADER', label: 'HEADER', description: 'Authorization header bearer token' },
                  ]}
                />
              </Field>
              <Field label="Parameter Name" hint="Default: token">
                <input value={p.parameterName ?? ''} onChange={e => setP(prev => ({ ...prev, parameterName: e.target.value }))} placeholder="token" className={monoInputCls} />
              </Field>
            </div>
          )}

          {/* Basic Auth provider */}
          {p.type === 'BASIC' && (
            <div className="grid grid-cols-2 gap-4">
              <Field label="Username">
                <input value={p.username ?? ''} onChange={e => setP(prev => ({ ...prev, username: e.target.value }))} className={inputCls} />
              </Field>
              <Field label="Password" hint={p.password === SECRET_MASK ? 'Currently stored' : undefined}>
                <div className="relative">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    value={p.password ?? ''}
                    placeholder={p.password === SECRET_MASK ? '(unchanged)' : ''}
                    onChange={e => setP(prev => ({ ...prev, password: e.target.value }))}
                    className={`${inputCls} pr-9`}
                  />
                  <button type="button" onClick={() => setShowPassword(s => !s)} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-white">
                    {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </Field>
            </div>
          )}

          <ToggleRow label="Enabled" checked={p.enabled} onChange={v => setP(prev => ({ ...prev, enabled: v }))} />
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-white/[0.07] shrink-0">
          <button onClick={onClose} className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors">Cancel</button>
          <button
            onClick={() => { onSave(stripMaskedSecrets(p)); onClose() }}
            disabled={isPending || !p.name.trim()}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
          >
            <Key className="w-3.5 h-3.5" />
            {isPending ? 'Saving…' : 'Save Provider'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Main tab ─────────────────────────────────────────────────────────────────

interface Props {
  initial: GatewayAuthProvider[]
  onUpsert: (p: GatewayAuthProvider) => void
  onDelete: (id: string) => void
  isPending: boolean
}

export default function AuthProvidersTab({ initial, onUpsert, onDelete, isPending }: Props) {
  const [editing, setEditing] = useState<{ provider: GatewayAuthProvider; isNew: boolean } | null>(null)

  const newProvider = (): GatewayAuthProvider => ({
    id: `ap-${Date.now()}`,
    name: '',
    type: 'JWT_VERIFY',
    enabled: true,
  })

  return (
    <div className="max-w-3xl">
      <SectionHeader
        icon={Lock}
        title="Auth Providers"
        description="Configure upstream authentication and token verification providers. Filter definitions reference these providers by name."
        actions={
          <button
            onClick={() => setEditing({ provider: newProvider(), isNew: true })}
            className="flex items-center gap-2 px-3 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" /> Add Provider
          </button>
        }
      />

      {initial.length === 0 ? (
        <EmptyState
          icon={Lock}
          title="No auth providers configured"
          description="Add a JWT JWKS, OAuth2, or Basic Auth provider to enable authentication filter references."
          action={
            <button
              onClick={() => setEditing({ provider: newProvider(), isNew: true })}
              className="flex items-center gap-2 px-3 py-2 bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/30 text-indigo-400 text-xs font-medium rounded-lg transition-all"
            >
              <Plus className="w-3.5 h-3.5" /> Add first provider
            </button>
          }
        />
      ) : (
        <div className="space-y-3">
          {initial.map(p => {
            return (
              <div
                key={p.id}
                className="bg-white/[0.03] rounded-xl border border-white/[0.06] p-4 hover:border-white/[0.09] transition-colors"
              >
                <div className="flex items-center justify-between gap-3">
                  {/* Left: identity */}
                  <div className="flex items-center gap-3 min-w-0">
                    <div className={`w-2 h-2 rounded-full shrink-0 ${p.enabled ? 'bg-emerald-400' : 'bg-gray-600'}`} />
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-semibold text-white">{p.name}</span>
                        <span className={`text-[10px] px-2 py-0.5 rounded font-mono font-medium border ${TYPE_COLORS[p.type] ?? 'text-gray-400 bg-white/5 border-white/10'}`}>
                          {TYPE_LABELS[p.type] ?? p.type}
                        </span>
                      </div>
                      <div className="text-xs text-gray-500 mt-0.5 font-mono truncate">
                        {p.jwksUri ?? p.uri ?? (p.username ? `user: ${p.username}` : '—')}
                      </div>
                    </div>
                  </div>
                  {/* Right: actions */}
                  <div className="flex items-center gap-1 shrink-0">
                    <button
                      onClick={() => setEditing({ provider: p, isNew: false })}
                      className="p-1.5 text-gray-400 hover:text-white hover:bg-white/[0.05] rounded-lg transition-colors"
                    >
                      <Edit className="w-3.5 h-3.5" />
                    </button>
                    <button
                      onClick={() => {
                        if (!confirm(`Delete provider "${p.name}"?`)) return
                        onDelete(p.id)
                      }}
                      className="p-1.5 text-red-400/70 hover:text-red-400 hover:bg-red-400/10 rounded-lg transition-colors"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      )}

      {editing && (
        <ProviderModal
          provider={editing.provider}
          isNew={editing.isNew}
          isPending={isPending}
          onSave={onUpsert}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  )
}

