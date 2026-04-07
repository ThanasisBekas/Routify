# Initiative GF-04 — JwtAuth Filter Hardening

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 2 (High-Priority Filters) · **Owner:** Gateway + Identity teams  
> **Category:** Authentication · **Priority:** High

---

## Problem Statement

1. **Dev-mode unsigned path** — when `routify.jwt.public-key` is empty, the filter decodes the JWT payload *without signature verification*. This is a security risk if the config is accidentally left unset in production.
2. **No JWKS URI support** — the public key is loaded once from a Base64 config property. Key rotation requires a gateway restart.
3. **Issuer/audience claims are config params** but never actually validated against the parsed JWT.
4. **HS256 algorithm advertised in config** but the implementation only handles RS256.

## Solution Overview

Remove the unsigned JWT decode path (fail-closed security), add JWKS URI support for key rotation, enforce issuer/audience validation, and simplify to RS256-only.

---

## Detailed Implementation Steps

### Step 1: Remove Unsigned Decode Path

**Files to modify:**
- `routify-api-gateway/.../filter/auth/JwtAuthGatewayFilterFactory.java`

**Changes:**
- If both `routify.jwt.public-key` and `routify.jwt.jwks-uri` are blank at startup → log `ERROR` and register a filter that rejects all JWT requests with a `SERVER_MISCONFIGURED` error code and HTTP 500.
- Remove the `if (publicKey == null) { decodeWithoutVerification() }` branch entirely.
- Add a `@PostConstruct` health check that validates at least one key source is configured.

**Task list:**
- [x] Remove unsigned JWT decode path
- [x] Add startup validation: fail if neither public key nor JWKS URI is configured
- [x] Log `ERROR` with clear remediation message
- [x] Return `SERVER_MISCONFIGURED` error via `GatewayProblemResponse` (from GF-02)

---

### Step 2: Add JWKS URI Support

**Files to modify:**
- `routify-api-gateway/.../filter/auth/JwtAuthGatewayFilterFactory.java`
- `routify-api-gateway/.../config/GatewayProperties.java` (or equivalent JWT config)

**New config property:**
```yaml
routify:
  jwt:
    jwks-uri: ""           # e.g., https://auth.example.com/.well-known/jwks.json
    jwks-cache-minutes: 5  # JWKS refresh interval
```

**Implementation:**
- Use JJWT's `JwkSetSource` or a custom `WebClient`-based fetcher to retrieve the JWKS endpoint.
- Cache the key set with a configurable TTL (default 5 minutes).
- On JWT verification: resolve the signing key by matching the `kid` header claim against the cached JWKS.
- If both static `public-key` and `jwks-uri` are configured, prefer JWKS URI; fall back to static key if JWKS fetch fails.

**Task list:**
- [x] Add `jwks-uri` and `jwks-cache-minutes` config properties
- [x] Implement JWKS fetcher with caching (Caffeine or scheduled refresh)
- [x] Resolve signing key by `kid` from JWKS
- [x] Fallback to static public key if JWKS fetch fails
- [x] Non-blocking JWKS fetch using `WebClient`

---

### Step 3: Enforce Issuer & Audience Validation

**Files to modify:**
- `routify-api-gateway/.../filter/auth/JwtAuthGatewayFilterFactory.java`

**Changes:**
- When `config.issuer` is set, validate `claims.getIssuer().equals(config.issuer)`. Reject with `INVALID_ISSUER` error code if mismatched.
- When `config.audience` is set, validate `claims.getAudience().contains(config.audience)`. Reject with `INVALID_AUDIENCE` error code if missing.
- Both validations happen after signature verification, before further claims processing.

**Task list:**
- [x] Enforce issuer validation when configured
- [x] Enforce audience validation when configured
- [x] Return specific error codes (`INVALID_ISSUER`, `INVALID_AUDIENCE`)

---

### Step 4: Simplify to RS256-Only

**Files to modify:**
- `routify-api-gateway/.../filter/auth/JwtAuthGatewayFilterFactory.java`

**Changes:**
- Remove HS256 from configuration options and documentation.
- Hardcode `SignatureAlgorithm.RS256` in the JWT parser builder.
- Log a `WARN` at startup if `algorithm: HS256` is configured (backward compat migration aid).

**Task list:**
- [x] Remove HS256 support from filter
- [x] Log migration warning if HS256 is configured
- [x] Update documentation

---

### Step 5: Add `require-jti` Config Flag

**Files to modify:**
- `routify-api-gateway/.../filter/auth/JwtAuthGatewayFilterFactory.java`

**New config parameter:**
```yaml
routify:
  jwt:
    require-jti: true  # default
```

When `true`, reject tokens without a `jti` claim with `MISSING_JTI` error code instead of logging a warning and skipping the blocklist check.

**Task list:**
- [x] Add `require-jti` config property (default `true`)
- [x] Reject tokens without `jti` when `require-jti=true`
- [x] Return `MISSING_JTI` error code

---

### Step 6: Tests

**Files to create/modify:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/auth/JwtAuthHardeningTest.java`

**Test cases:**
- No public key and no JWKS URI → startup health check fails
- JWKS URI configured → keys fetched and cached, JWT verified
- JWKS key rotation → new key picked up within cache TTL
- Issuer mismatch → 401 with `INVALID_ISSUER`
- Audience mismatch → 401 with `INVALID_AUDIENCE`
- Missing `jti` with `require-jti=true` → 401 with `MISSING_JTI`
- Missing `jti` with `require-jti=false` → warning logged, request allowed
- HS256 config → startup warning logged

**Task list:**
- [x] Write tests for all hardening scenarios
- [x] Verify no behavioral regression for valid RS256 tokens
- [x] Test JWKS fetch failure fallback to static key

---

## Acceptance Criteria

- [x] No unsigned JWT decode path in production
- [x] JWKS URI support with key caching and rotation
- [x] Issuer and audience claims validated when configured
- [x] `require-jti=true` by default; tokens without `jti` rejected
- [x] Startup health check fails if neither public key nor JWKS URI is configured
