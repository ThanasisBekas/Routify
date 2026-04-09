/**
 * Utility functions & constants for gateway config ref resolution.
 *
 * Extracted from GatewayConfigRefPanel to satisfy the react-refresh/only-export-components rule
 * (component files should only export components).
 */
import { Shield, Gauge, KeyRound, ShieldCheck, Zap, ServerCog } from 'lucide-react'
import type { FilterType } from '../../types'

// ─── Ref type mapping per filter type ─────────────────────────────────────────

export interface RefTypeSpec {
  refType: string
  label: string
  description: string
  icon: React.ElementType
  /** Whether this ref type targets a single global entry (no picker needed). */
  singleton?: boolean
}

/**
 * Maps each filter type to the gateway config ref type(s) it supports.
 * Filter types not in this map do not support gateway config refs.
 */
export const FILTER_REF_MAP: Partial<Record<FilterType, RefTypeSpec>> = {
  // Auth providers
  AUTH_BASIC: {
    refType: 'AUTH_PROVIDER',
    label: 'Auth Provider',
    description: 'Import credentials from a BASIC auth provider in Gateway Config',
    icon: Shield,
  },
  AUTH_OAUTH2: {
    refType: 'AUTH_PROVIDER',
    label: 'Auth Provider',
    description: 'Import OAuth2 introspection config from a gateway auth provider',
    icon: Shield,
  },
  AUTH_MTLS: {
    refType: 'MTLS_CLIENT_MAPPING',
    label: 'mTLS Auth Provider',
    description: 'Import client-certificate mappings from an MTLS auth provider',
    icon: ShieldCheck,
  },
  AUTH_CLIENT_ID: {
    refType: 'CLIENT_ID_MAPPING',
    label: 'Client ID Auth Provider',
    description: 'Import client-ID entries from a CLIENT_ID auth provider',
    icon: ShieldCheck,
  },
  // Cert vault
  AUTH_CERT_VAULT: {
    refType: 'VAULT_CERT',
    label: 'Certificate Group',
    description: 'Link to a Certificate Group logical ID from the Cert Vault',
    icon: KeyRound,
  },
  CERT_ROTATION: {
    refType: 'VAULT_CERT',
    label: 'Certificate Group',
    description: 'Link to a Certificate Group logical ID from the Cert Vault',
    icon: KeyRound,
  },
  CERT_VAULT_EXPIRY_CHECK: {
    refType: 'VAULT_CERT',
    label: 'Certificate Group',
    description: 'Link to a Certificate Group logical ID from the Cert Vault',
    icon: KeyRound,
  },
  // Rate limiting
  RATE_LIMIT_FIXED_WINDOW: {
    refType: 'RATE_LIMIT_POLICY',
    label: 'Rate Limit Policy',
    description: 'Import rate-limit values from a policy in Gateway Config',
    icon: Gauge,
  },
  RATE_LIMIT_SLIDING_WINDOW: {
    refType: 'RATE_LIMIT_POLICY',
    label: 'Rate Limit Policy',
    description: 'Import rate-limit values from a policy in Gateway Config',
    icon: Gauge,
  },
  // Downstream auth
  DOWNSTREAM_BASIC_AUTH: {
    refType: 'DOWNSTREAM_CREDENTIAL',
    label: 'Downstream Credential',
    description: 'Import credentials from a downstream credential entry in Gateway Config',
    icon: KeyRound,
  },
  DOWNSTREAM_BEARER_CC: {
    refType: 'DOWNSTREAM_OAUTH2_PROVIDER',
    label: 'Downstream OAuth2 Provider',
    description: 'Import OAuth2 client-credentials config from a downstream provider',
    icon: KeyRound,
  },
  // Resilience (singletons — no picker, auto-link to global defaults)
  CIRCUIT_BREAKER_V2: {
    refType: 'CIRCUIT_BREAKER_DEFAULTS',
    label: 'Circuit Breaker Defaults',
    description: 'Import threshold and window defaults from Gateway Config',
    icon: Zap,
    singleton: true,
  },
  RETRY_V2: {
    refType: 'RESILIENCE_DEFAULTS',
    label: 'Resilience Defaults',
    description: 'Import retry/timeout defaults from Gateway Config',
    icon: ServerCog,
    singleton: true,
  },
  TIMEOUT: {
    refType: 'RESILIENCE_DEFAULTS',
    label: 'Resilience Defaults',
    description: 'Import timeout defaults from Gateway Config',
    icon: ServerCog,
    singleton: true,
  },
}

/** Returns whether a filter type supports gateway config refs. */
export function supportsConfigRef(filterType: FilterType): boolean {
  return filterType in FILTER_REF_MAP
}

/** Returns the ref type spec for a filter type, or undefined if not supported. */
export function getRefSpec(filterType: FilterType): RefTypeSpec | undefined {
  return FILTER_REF_MAP[filterType]
}
