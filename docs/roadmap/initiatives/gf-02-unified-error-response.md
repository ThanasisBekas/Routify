# Initiative GF-02 — Unified Error Response Builder

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 1 (Foundation) · **Owner:** Gateway team  
> **Category:** Cross-cutting · **Priority:** High

---

## Problem Statement

Every filter that short-circuits a request (auth failures, rate limit, validation, AI block, SpEL reject) writes its own RFC 9457 response body using local `String.formatted()` templates. Inconsistencies include:
- Some include `errorCode`, others don't.
- JSON escaping of user-supplied `detail` strings is ad hoc (`replace("\"", "'")` in AI filter, `replace("\"", "\\\"")` in SpEL filter).
- `Content-Type` header is set to `application/problem+json` in some filters, `application/json` in others.
- `Retry-After` header is missing from rate limit rejection responses.

## Solution Overview

Create a shared `GatewayProblemResponse` utility with a reactive builder API that serializes RFC 9457 ProblemDetail responses using Jackson `ObjectMapper`. All filter factories migrate to use this builder, ensuring consistent, safe, and standards-compliant error responses.

---

## Detailed Implementation Steps

### Step 1: Create `GatewayProblemResponse` Utility

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/shared/GatewayProblemResponse.java`

**API design:**
```java
public final class GatewayProblemResponse {

    public static Builder status(HttpStatus status) { ... }

    public static final class Builder {
        Builder errorCode(String code);
        Builder detail(String detail);
        Builder detail(String format, Object... args);
        Builder header(String name, String value);
        Builder header(String name, long value);
        Builder type(String typeUri);
        Builder instance(String instanceUri);
        Builder extension(String key, Object value);
        Mono<Void> write(ServerWebExchange exchange);
    }
}
```

**Key requirements:**
1. Use Jackson `ObjectMapper` for body serialization — no string templates.
2. Always set `Content-Type: application/problem+json`.
3. JSON-escape all fields via Jackson — no manual escaping.
4. Support arbitrary response headers (e.g., `Retry-After`, `X-RateLimit-*`).
5. Complete the response: set status code, write body, return `Mono<Void>`.

**Task list:**
- [ ] Create `GatewayProblemResponse` with builder pattern
- [ ] Use Jackson `ObjectMapper` (inject via `@Autowired` or static singleton)
- [ ] Standardize `Content-Type` to `application/problem+json`
- [ ] Support `Retry-After` and custom headers
- [ ] Support RFC 9457 extension fields via `extension(key, value)`

---

### Step 2: Migrate Authentication Filters

**Files to modify:**
- `JwtAuthGatewayFilterFactory.java`
- `ApiKeyAuthGatewayFilterFactory.java`
- `BasicAuthGatewayFilterFactory.java`
- `MtlsAuthGatewayFilterFactory.java`
- `OAuth2TokenIntrospectGatewayFilterFactory.java`
- `ClientIdAuthGatewayFilterFactory.java`
- `CertVaultAuthGatewayFilterFactory.java`

**Migration pattern:**
```java
// Before:
String body = String.formatted("""
    {"type":"...", "status":401, "detail":"%s"}""", detail.replace("\"", "'"));
response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
return response.writeWith(Mono.just(buffer));

// After:
return GatewayProblemResponse.status(HttpStatus.UNAUTHORIZED)
    .errorCode("AUTH_FAILED")
    .detail(detail)
    .write(exchange);
```

**Task list:**
- [ ] Migrate all 7 authentication filter factories
- [ ] Verify error code consistency across auth filters
- [ ] Remove inline JSON template code

---

### Step 3: Migrate Rate Limit Filters

**Files to modify:**
- `FixedWindowRateLimitGatewayFilterFactory.java`
- `SlidingWindowRateLimitGatewayFilterFactory.java`

**Additional headers for rate limit responses:**
- `Retry-After: <seconds-until-window-reset>`
- `X-RateLimit-Limit: <maxRequests>` (if headers enabled — see GF-03)

**Task list:**
- [ ] Migrate both rate limit filter factories
- [ ] Add `Retry-After` header on 429 responses
- [ ] Remove inline JSON template code

---

### Step 4: Migrate Validation, AI, and Custom Filters

**Files to modify:**
- `JsonSchemaValidateGatewayFilterFactory.java`
- `SpelCustomGatewayFilterFactory.java`
- `AiGatewayFilterFactory.java`
- `AiModifierGatewayFilterFactory.java`
- `ConditionalRouteGatewayFilterFactory.java`

**Task list:**
- [ ] Migrate all 5 remaining filter factories that produce error responses
- [ ] Remove ad hoc JSON escaping (`replace("\"", "'")`, `replace("\"", "\\\"")`)
- [ ] Standardize error codes per filter category

---

### Step 5: Security Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/shared/GatewayProblemResponseTest.java`

**Test cases:**
- Standard ProblemDetail fields serialized correctly
- Malicious `detail` strings (containing `"`, `\`, `<script>`, Unicode) properly JSON-escaped
- `Content-Type` is always `application/problem+json`
- Custom headers are set on the response
- `Retry-After` header present on 429 responses
- Extension fields appear in response body

**Task list:**
- [ ] Write unit tests for `GatewayProblemResponse` builder
- [ ] Write JSON injection tests with adversarial input
- [ ] Write integration tests verifying migrated filters produce consistent responses

---

## Acceptance Criteria

- [ ] `GatewayProblemResponse` class with builder API
- [ ] All 12 filter factories that produce error responses migrated
- [ ] JSON injection tests (malicious `detail` strings) pass
- [ ] `Content-Type` is always `application/problem+json`
- [ ] `Retry-After` header present on all 429 responses
- [ ] Average lines of error response code per filter drops from ~12 to ~1

