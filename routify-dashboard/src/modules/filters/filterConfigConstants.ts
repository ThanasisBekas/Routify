import type { FilterType } from '../../types'

// ─── Shared CSS class constants ───────────────────────────────────────────────

export const inputCls =
  'w-full bg-white/[0.04] border border-white/8 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all'
export const monoInputCls = `${inputCls} font-mono text-xs`
/** @deprecated – use the <Select> component instead */
export const selectCls =
  'w-full bg-[#0c0e14] border border-white/8 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition-all'

// ─── Config state type — one key per filter type ──────────────────────────────

export type FilterConfig = Record<string, unknown>

// ─── Default configs per filter type ─────────────────────────────────────────

export const DEFAULT_CONFIGS: Partial<Record<FilterType, FilterConfig>> = {
  // Authentication — field names match backend Config classes exactly
  AUTH_JWT: { issuer: '', audience: '', algorithm: 'RS256' },
  AUTH_API_KEY: { headerName: 'X-API-Key', queryParam: '', validationMode: 'REDIS' },
  AUTH_BASIC: { username: '', password: '' },
  AUTH_OAUTH2: { providerName: '', claimsToHeaderMapping: {} },
  AUTH_MTLS: { values: [] },
  AUTH_CLIENT_ID: { values: [] },
  AUTH_CERT_VAULT: {
    logicalId: '',
    certificateHeader: 'X-Client-Certificate',
    requireCertificate: true,
    stripCertificateHeader: false,
  },
  // Downstream Auth
  DOWNSTREAM_BASIC_AUTH: { username: '', password: '' },
  DOWNSTREAM_BEARER_CC: { oauth2ProviderName: '', forwardCallerAuth: false },
  // Rate Limiting
  RATE_LIMIT_FIXED_WINDOW: { maxRequests: 100, windowMs: 60000, keyResolver: 'IP', includeHeaders: true },
  RATE_LIMIT_SLIDING_WINDOW: { maxRequests: 100, windowMs: 60000, keyResolver: 'IP', includeHeaders: true },
  // Modification
  REQUEST_HEADER_MODIFY: { add: {}, set: {}, remove: {} },
  RESPONSE_HEADER_MODIFY: { add: {}, set: {}, remove: {} },
  // Transformation
  BODY_JOLT_TRANSFORM: { spec: '[]', phase: 'REQUEST' },
  // Validation
  VALIDATE_JSON_SCHEMA: { schema: '{}', specVersion: 'V7' },
  // Resilience
  TIMEOUT: { timeoutMs: 30000 },
  // Observability
  CORRELATION_ID: {},
  REQUEST_LOGGER: {
    logRequestHeaders: true,
    logResponseHeaders: true,
    logRequestBody: false,
    logResponseBody: false,
    maxBodyLogSize: 4096,
    failedStatusThreshold: 500,
  },
  TENANT_CONTEXT: {},
  SECURITY_HEADERS: {},
  CUSTOM_METRIC: { metricName: '', description: '', tags: {} },
  // Security
  CERT_ROTATION: { logicalId: '', certificateHeader: 'X-Client-Certificate' },
  CERT_VAULT_EXPIRY_CHECK: { logicalId: '', warningDays: 30, rejectOnExpiringSoon: false, injectMetadataHeaders: true },
  // Versioning
  API_VERSIONING: {
    version: 'v1',
    strategy: 'HEADER',
    versionHeader: 'X-Api-Version',
    versionParam: 'version',
    versionPrefix: '',
  },
  // Routing
  CONDITIONAL_ROUTE: { conditionHeader: '', conditionParam: '', conditionPattern: '.*', alternativeUri: '' },
  USER_ID_PAYLOAD_ROUTING: { enabled: true, userIdField: 'userId', allowlistUserIds: [], alternativeUri: '' },
  // Custom
  CUSTOM_SPEL: { expression: '', description: '' },
  // AI
  AI_FILTER: {
    policyDescription: '',
    evaluationMode: 'SYNC',
    includeBody: false,
    maxBodyBytes: 512,
    fallbackAction: 'ALLOW',
    confidenceThreshold: 0.85,
    cacheEnabled: true,
    cacheTtlSeconds: 30,
  },
  AI_MODIFIER: {
    modificationPrompt: '',
    targetFields: 'BODY',
    modelId: '',
    temperature: 0.1,
    maxTokens: 1024,
    timeoutMs: 4000,
    fallbackBehavior: 'PASSTHROUGH',
    includeBody: true,
    maxBodyBytes: 2048,
    cacheEnabled: false,
    cacheTtlSeconds: 60,
  },
}
