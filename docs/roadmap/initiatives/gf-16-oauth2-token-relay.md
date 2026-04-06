# Initiative GF-16 — OAuth2 Token Relay Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 5 (Extensions) · **Owner:** Gateway + Identity teams  
> **Category:** Authentication · **Priority:** Medium  
> **Filter type:** `OAUTH2_TOKEN_RELAY`

---

## Problem Statement

When the gateway sits between a client and a downstream service that uses a different identity provider or requires different token scopes, there is no way to exchange the incoming bearer token for a downstream-specific token at the gateway level. Operators must implement token exchange logic in each downstream service, duplicating OAuth2 client credentials configuration.

## Solution Overview

A filter implementing RFC 8693 Token Exchange — exchanges the incoming bearer token for a downstream-specific token via a configured OAuth2 token endpoint. Includes token caching, fully non-blocking `WebClient` calls, and configurable fallback behavior.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/auth/OAuth2TokenRelayGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `tokenEndpoint` | `String` | (required) | OAuth2 token endpoint URL |
| `clientId` | `String` | (required) | Client ID for exchange |
| `clientSecret` | `String` | (required, sensitive) | Client secret |
| `subjectTokenType` | `String` | `urn:ietf:params:oauth:token-type:access_token` | Subject token type |
| `requestedTokenType` | `String` | `urn:ietf:params:oauth:token-type:access_token` | Requested token type |
| `scope` | `String` | `""` | Scopes for exchanged token |
| `audience` | `String` | `""` | Target audience |
| `cacheTtlSeconds` | `int` | `300` | Token cache TTL |

**Implementation:**
1. Extract incoming bearer token from `Authorization` header.
2. Check token cache for a cached exchanged token.
3. On cache miss: call token endpoint via `WebClient` with RFC 8693 token exchange grant.
4. Cache the exchanged token with `TTL = min(cacheTtlSeconds, token.expires_in - 30s)`.
5. Replace `Authorization: Bearer <original>` with `Authorization: Bearer <exchanged>`.

**Token exchange request (RFC 8693):**
```
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&subject_token=<incoming_token>
&subject_token_type=urn:ietf:params:oauth:token-type:access_token
&requested_token_type=urn:ietf:params:oauth:token-type:access_token
&client_id=<clientId>
&client_secret=<clientSecret>
&scope=<scope>
&audience=<audience>
```

**Task list:**
- [ ] Create filter factory
- [ ] Implement RFC 8693 token exchange via `WebClient`
- [ ] Replace `Authorization` header with exchanged token
- [ ] Fully non-blocking (reactive `WebClient`)

---

### Step 2: Token Cache

**Implementation:**
- Caffeine in-process cache keyed by `SHA-256(incoming_token + audience)`.
- TTL = `min(cacheTtlSeconds, exchanged_token.expires_in - 30s)` — ensures cached token doesn't outlive its validity.
- Cache size limited to 10,000 entries (configurable).

**Task list:**
- [ ] Implement Caffeine token cache
- [ ] Key by hash of incoming token + audience
- [ ] Respect token expiry for cache TTL
- [ ] Configurable max cache size

---

### Step 3: Fallback Behavior

**On token exchange failure, configurable fallback:**
- `REJECT` (default) — return 401 Unauthorized via `GatewayProblemResponse`
- `PASS_THROUGH` — forward the original token unchanged
- `STRIP` — remove `Authorization` header entirely

**New config param:**
```yaml
fallbackMode: REJECT  # REJECT | PASS_THROUGH | STRIP
```

**Task list:**
- [ ] Add `fallbackMode` config parameter
- [ ] Implement `REJECT` fallback (401 response)
- [ ] Implement `PASS_THROUGH` fallback
- [ ] Implement `STRIP` fallback

---

### Step 4: Sensitive Config Handling

The `clientSecret` field must use the `@SensitiveField` annotation for masking when returned via admin-api. On filter config save, check `Sensitive.isMasked(clientSecret)` before overwriting.

**Task list:**
- [ ] Mark `clientSecret` as `@SensitiveField`
- [ ] Handle masked values on update

---

### Step 5: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `OAUTH2_TOKEN_RELAY`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [ ] Add `OAUTH2_TOKEN_RELAY` to `FilterType` enum
- [ ] Add to TypeScript `FilterType` union
- [ ] Add filter config form in dashboard (with secret input)

---

### Step 6: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/auth/OAuth2TokenRelayTest.java`

**Test cases:**
- Token exchange success → downstream receives exchanged token
- Token cache hit → no token endpoint call
- Cache TTL expiry → new exchange call
- Token exchange failure with `REJECT` → 401
- Token exchange failure with `PASS_THROUGH` → original token forwarded
- Token exchange failure with `STRIP` → no Authorization header
- `clientSecret` masked in API responses

**Task list:**
- [ ] Write token exchange success/failure tests (mock token endpoint)
- [ ] Write cache behavior tests
- [ ] Write fallback mode tests

---

## Acceptance Criteria

- [ ] Incoming bearer token exchanged for downstream-specific token via RFC 8693
- [ ] Token cache avoids per-request exchange calls
- [ ] Fully non-blocking `WebClient` calls
- [ ] Configurable fallback on exchange failure (REJECT, PASS_THROUGH, STRIP)
- [ ] `clientSecret` masked via `@SensitiveField`

