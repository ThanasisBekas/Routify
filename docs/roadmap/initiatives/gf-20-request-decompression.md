# Initiative GF-20 — Request Decompression Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 3 (Resilience & Performance) · **Owner:** Gateway team  
> **Category:** Performance · **Priority:** Medium  
> **Filter type:** `REQUEST_DECOMPRESS`

---

## Problem Statement

Mobile and IoT clients often compress request payloads to reduce bandwidth consumption. Many backend services, however, do not support compressed request bodies and expect uncompressed JSON/XML. Operators must either configure decompression at the load balancer (losing Routify visibility) or require clients to send uncompressed payloads (wasting bandwidth).

## Solution Overview

A filter that transparently decompresses `gzip`, `br` (Brotli), and `zstd` encoded request bodies before forwarding to upstream, with zip bomb protection via a `maxDecompressedSize` limit.

---

## Detailed Implementation Steps

### Step 1: Add Compression Library Dependencies

**Files to modify:**
- `routify-api-gateway/pom.xml`

**Dependencies:**
```xml
<!-- Brotli decompressor -->
<dependency>
    <groupId>org.brotli</groupId>
    <artifactId>dec</artifactId>
    <version>0.1.2</version>
</dependency>
<!-- Zstandard decompressor -->
<dependency>
    <groupId>com.github.luben</groupId>
    <artifactId>zstd-jni</artifactId>
    <version>1.5.6-6</version>
</dependency>
```

Note: `gzip` uses `java.util.zip.GZIPInputStream` (JDK built-in, no extra dependency).

**Task list:**
- [ ] Add `org.brotli:dec` dependency
- [ ] Add `com.github.luben:zstd-jni` dependency
- [ ] Verify no dependency conflicts

---

### Step 2: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/performance/RequestDecompressGatewayFilterFactory.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `supportedEncodings` | `String` | `gzip,br,zstd` | Content-Encoding types to decompress |
| `maxDecompressedSize` | `String` | `10MB` | Max decompressed body size |
| `removeEncoding` | `boolean` | `true` | Remove `Content-Encoding` header |
| `updateContentLength` | `boolean` | `true` | Update `Content-Length` header |

**Implementation:**
1. Check `Content-Encoding` request header.
2. If encoding matches a supported type → wrap request body with decompression stream.
3. Track decompressed bytes; abort with 413 if `maxDecompressedSize` exceeded.
4. Wrap in `ServerHttpRequestDecorator` to serve the decompressed body.
5. If `removeEncoding=true`, remove `Content-Encoding` header.
6. If `updateContentLength=true`, update `Content-Length` to decompressed size.
7. Add `X-Original-Encoding: <encoding>` header for downstream observability.

**Task list:**
- [ ] Create filter factory
- [ ] Implement `gzip` decompression via `GZIPInputStream`
- [ ] Implement `br` decompression via Brotli library
- [ ] Implement `zstd` decompression via zstd-jni
- [ ] Wrap decompressed body in `ServerHttpRequestDecorator`

---

### Step 3: Zip Bomb Protection

**Implementation:**
- Stream-based byte counting during decompression.
- Maintain a running total of decompressed bytes.
- If total exceeds `maxDecompressedSize` → abort immediately with 413 Payload Too Large.
- Never buffer the entire decompressed payload in memory — use streaming decompression.

**Task list:**
- [ ] Implement streaming byte counter
- [ ] Abort with 413 on exceeded limit
- [ ] Use `GatewayProblemResponse` for error response
- [ ] Never buffer full payload in memory

---

### Step 4: Header Cleanup

**Implementation:**
After decompression:
- Remove `Content-Encoding` header (if `removeEncoding=true`).
- Update `Content-Length` to the decompressed size (if `updateContentLength=true` and size is known).
- Add `X-Original-Encoding: gzip` (or `br`/`zstd`) header.

**Task list:**
- [ ] Remove `Content-Encoding` when configured
- [ ] Update `Content-Length` when configured
- [ ] Add `X-Original-Encoding` header

---

### Step 5: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `REQUEST_DECOMPRESS`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [ ] Add `REQUEST_DECOMPRESS` to `FilterType` enum
- [ ] Add to TypeScript `FilterType` union
- [ ] Add filter config form in dashboard

---

### Step 6: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/performance/RequestDecompressTest.java`

**Test cases:**
- Gzip-compressed body decompressed correctly
- Brotli-compressed body decompressed correctly
- Zstd-compressed body decompressed correctly
- Uncompressed body (no `Content-Encoding`) passes through unchanged
- Unsupported encoding (`deflate`) passes through unchanged
- Zip bomb exceeding `maxDecompressedSize` → 413
- `Content-Encoding` removed after decompression
- `Content-Length` updated after decompression
- `X-Original-Encoding` header injected
- Upstream receives readable decompressed body

**Task list:**
- [ ] Write decompression tests for each encoding
- [ ] Write zip bomb protection tests
- [ ] Write header cleanup tests
- [ ] Write passthrough tests for unsupported encodings

---

## Acceptance Criteria

- [ ] `gzip`, `br`, and `zstd` request bodies decompressed transparently
- [ ] Zip bomb protection with `maxDecompressedSize` limit
- [ ] `Content-Encoding` removed and `Content-Length` updated
- [ ] `X-Original-Encoding` header for downstream observability
- [ ] Unsupported encodings pass through unchanged
- [ ] Streaming decompression — never buffers entire payload

