# Initiative GF-20 — Request Decompression Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 3 (Resilience & Performance) · **Owner:** Gateway team  
> **Category:** Performance · **Priority:** Medium  
> **Filter type:** `REQUEST_DECOMPRESS`  
> **Status:** ✅ **COMPLETED**

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
- [x] Add `org.brotli:dec` dependency
- [x] Add `com.github.luben:zstd-jni` dependency
- [x] Verify no dependency conflicts

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
- [x] Create filter factory
- [x] Implement `gzip` decompression via `GZIPInputStream`
- [x] Implement `br` decompression via Brotli library
- [x] Implement `zstd` decompression via zstd-jni
- [x] Wrap decompressed body in `ServerHttpRequestDecorator`

---

### Step 3: Zip Bomb Protection

**Implementation:**
- Stream-based byte counting during decompression.
- Maintain a running total of decompressed bytes.
- If total exceeds `maxDecompressedSize` → abort immediately with 413 Payload Too Large.
- Never buffer the entire decompressed payload in memory — use streaming decompression.

**Task list:**
- [x] Implement streaming byte counter
- [x] Abort with 413 on exceeded limit
- [x] Use `GatewayProblemResponse` for error response
- [x] Never buffer full payload in memory

---

### Step 4: Header Cleanup

**Implementation:**
After decompression:
- Remove `Content-Encoding` header (if `removeEncoding=true`).
- Update `Content-Length` to the decompressed size (if `updateContentLength=true` and size is known).
- Add `X-Original-Encoding: gzip` (or `br`/`zstd`) header.

**Task list:**
- [x] Remove `Content-Encoding` when configured
- [x] Update `Content-Length` when configured
- [x] Add `X-Original-Encoding` header

---

### Step 5: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `REQUEST_DECOMPRESS`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `REQUEST_DECOMPRESS` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form in dashboard

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
- [x] Write decompression tests for each encoding
- [x] Write zip bomb protection tests
- [x] Write header cleanup tests
- [x] Write passthrough tests for unsupported encodings

---

## Acceptance Criteria

- [x] `gzip`, `br`, and `zstd` request bodies decompressed transparently
- [x] Zip bomb protection with `maxDecompressedSize` limit
- [x] `Content-Encoding` removed and `Content-Length` updated
- [x] `X-Original-Encoding` header for downstream observability
- [x] Unsupported encodings pass through unchanged
- [x] Streaming decompression — never buffers entire payload
