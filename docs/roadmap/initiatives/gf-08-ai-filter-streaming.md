# Initiative GF-08 — AI Filter Streaming Body Support

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 4 (Advanced Features) · **Owner:** Gateway + AI teams  
> **Category:** AI · **Priority:** Medium

---

## Problem Statement

When `includeBody=true`, the AI filter reads the body excerpt from an exchange attribute (`AI_FILTER_BODY_EXCERPT`) that must be pre-cached by an earlier filter. For large request bodies, this buffering adds latency and memory pressure. The `maxBodyBytes=512` default is too small for useful context.

## Solution Overview

The AI filter reads and caches the body excerpt inline (no dependency on a pre-caching filter), increases the default excerpt size, adds content-type awareness, computes a body hash for cache keying, and re-emits the body for downstream consumption.

---

## Detailed Implementation Steps

### Step 1: Inline Body Reading

**Files to modify:**
- `routify-api-gateway/.../filter/ai/AiGatewayFilterFactory.java`
- `routify-api-gateway/.../filter/ai/AiModifierGatewayFilterFactory.java`

**Implementation:**
1. When `includeBody=true`, read the first `maxBodyBytes` from the request body using `DataBufferUtils.join()` with `limitRate()`.
2. Cache the excerpt bytes as an exchange attribute for potential reuse by the AI modifier filter.
3. Wrap the original request with a `ServerHttpRequestDecorator` that re-emits the cached bytes followed by the remaining body stream, ensuring upstream services receive the full payload.

**Task list:**
- [ ] Implement inline body reading in `AiGatewayFilterFactory`
- [ ] Implement inline body reading in `AiModifierGatewayFilterFactory`
- [ ] Remove dependency on external `AI_FILTER_BODY_EXCERPT` attribute
- [ ] Re-wrap body with `ServerHttpRequestDecorator` for downstream

---

### Step 2: Increase Default `maxBodyBytes`

**Config change:**
```yaml
maxBodyBytes: 2048  # was 512
```

2 KB is large enough for meaningful JSON payloads while remaining small enough to avoid memory pressure under high concurrency.

**Task list:**
- [ ] Change default `maxBodyBytes` from 512 to 2048
- [ ] Document the change in migration notes

---

### Step 3: Content-Type Awareness

**Implementation:**
Only include the body excerpt for textual content types:
- `application/json`
- `text/plain`
- `application/xml`
- `text/xml`

For binary content types (`application/octet-stream`, `image/*`, `multipart/form-data`, etc.), skip body reading entirely and set the body excerpt to `null` in the RPC request.

**Task list:**
- [ ] Check `Content-Type` before reading body
- [ ] Allow textual types: JSON, plain text, XML
- [ ] Skip binary types automatically
- [ ] Set body excerpt to `null` for skipped types

---

### Step 4: Body Hash for Cache Keying

**Implementation:**
- Compute SHA-256 hash of the body excerpt bytes.
- Include the hash in the `QueryRequest.AiFilterEvaluate` RPC message as a new `bodyHash` field.
- The AI service can use `bodyHash` as a cache key to avoid re-evaluating identical request bodies.
- Hash computation is performed inline (SHA-256 is fast, ~500 MB/s on modern CPUs).

**Task list:**
- [ ] Compute SHA-256 of body excerpt
- [ ] Add `bodyHash` field to `QueryRequest.AiFilterEvaluate`
- [ ] Add `bodyHash` field to `QueryRequest.AiModifierEvaluate`
- [ ] Update `routify-common` query request records

---

### Step 5: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/ai/AiFilterBodyStreamingTest.java`

**Test cases:**
- AI filter reads body inline without pre-caching filter
- Body excerpt limited to `maxBodyBytes`
- Binary content types (`image/png`) are automatically skipped
- JSON content type is read and included
- Body hash is consistent for identical payloads
- Upstream services receive the original full body unchanged
- Empty body handled gracefully
- Body reading does not block the Netty event loop

**Task list:**
- [ ] Write tests for inline body reading
- [ ] Write tests for content-type filtering
- [ ] Write tests for body hash computation
- [ ] Write tests for body re-emission to upstream
- [ ] Verify non-blocking behavior

---

## Acceptance Criteria

- [ ] AI filter reads body inline without requiring a pre-caching filter
- [ ] Binary content types are automatically skipped
- [ ] Body hash included in RPC request for cache keying
- [ ] Upstream services receive the original full body unchanged
- [ ] `maxBodyBytes=2048` by default

