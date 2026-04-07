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
  AUTH_JWT: { issuer: '', audience: '', requireJti: true },
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
  OAUTH2_TOKEN_RELAY: {
    tokenEndpoint: '',
    clientId: '',
    clientSecret: '',
    subjectTokenType: 'urn:ietf:params:oauth:token-type:access_token',
    requestedTokenType: 'urn:ietf:params:oauth:token-type:access_token',
    scope: '',
    audience: '',
    cacheTtlSeconds: 300,
    fallbackMode: 'REJECT',
  },
  // Rate Limiting
  RATE_LIMIT_FIXED_WINDOW: { maxRequests: 100, windowMs: 60000, keyResolver: 'IP', includeHeaders: true },
  RATE_LIMIT_SLIDING_WINDOW: { maxRequests: 100, windowMs: 60000, keyResolver: 'IP', includeHeaders: true },
  // Modification
  REQUEST_HEADER_MODIFY: { add: {}, set: {}, remove: {} },
  RESPONSE_HEADER_MODIFY: { add: {}, set: {}, remove: {} },
  // Transformation
  BODY_JOLT_TRANSFORM: { spec: '[]', phase: 'REQUEST', responseSpec: '[]', maxBodySize: 1048576 },
  // Validation
  VALIDATE_JSON_SCHEMA: { schema: '{}', specVersion: 'V7' },
  REQUEST_SIZE_LIMIT: { maxSize: '5MB', checkContentLength: true, checkActualSize: true, tenantAware: false },
  // Performance
  RESPONSE_CACHE: {
    ttlSeconds: 60,
    maxCachedBodySize: 65536,
    methods: 'GET',
    statusCodes: '200,206,301',
    keyStrategy: 'PATH_QUERY',
    varyHeaders: [],
    respectCacheControl: true,
    addCacheHeaders: true,
  },
  // Resilience
  TIMEOUT: { timeoutMs: 30000 },
  CIRCUIT_BREAKER_V2: {
    failureRateThreshold: 50.0,
    slowCallRateThreshold: 80.0,
    slowCallDurationMs: 3000,
    slidingWindowSize: 10,
    slidingWindowType: 'COUNT_BASED',
    minimumNumberOfCalls: 5,
    waitDurationInOpenStateMs: 60000,
    permittedNumberOfCallsInHalfOpenState: 3,
    fallbackStatus: 503,
    fallbackBody: '',
  },
  RETRY_V2: {
    maxRetries: 3,
    initialBackoffMs: 500,
    maxBackoffMs: 5000,
    backoffMultiplier: 2.0,
    jitterFactor: 0.25,
    retryableStatuses: '502,503,504',
    retryableMethods: 'GET,HEAD,OPTIONS',
    retryOnTimeout: true,
    idempotencyHeader: 'Idempotency-Key',
  },
  // Observability
  CORRELATION_ID: {},
  REQUEST_LOGGER: {
    logRequestHeaders: true,
    logResponseHeaders: true,
    logRequestBody: false,
    logResponseBody: false,
    maxBodyLogSize: 4096,
    maxBodyCaptureBytes: 4096,
    failedStatusThreshold: 500,
    samplingRate: 1.0,
    headerAllowlist: [],
    headerDenylist: [],
    skipPaths: [],
  },
  TENANT_CONTEXT: {},
  SECURITY_HEADERS: {},
  CUSTOM_METRIC: { metricName: '', description: '', tags: {} },
  BODY_SIZE_METRIC: { includeRequest: true, includeResponse: true, tags: {} },
  // Security
  CERT_ROTATION: { logicalId: '', certificateHeader: 'X-Client-Certificate' },
  CERT_VAULT_EXPIRY_CHECK: { logicalId: '', warningDays: 30, rejectOnExpiringSoon: false, injectMetadataHeaders: true },
  IP_ACCESS_CONTROL: {
    mode: 'DENYLIST',
    addresses: '',
    trustProxy: true,
    proxyDepth: 1,
    rejectStatus: 403,
    rejectMessage: 'Access denied',
  },
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
  GEO_ROUTE: {
    regions: '',
    defaultRegion: 'US',
    geoDbPath: 'classpath:GeoLite2-Country.mmdb',
    cacheSize: 10000,
  },
  // Custom
  CUSTOM_SPEL: { expression: '', description: '', maxExpressionLength: 500, maxPropertyDepth: 5, allowedFunctions: [] },
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
