# Initiative GF-09 — IP Allowlist / Denylist Filter

> **Parent:** [Gateway Filters Backlog Roadmap](../GATEWAY-FILTERS-ROADMAP.md) · **Wave:** 2 (High-Priority Filters) · **Owner:** Gateway team  
> **Category:** Security · **Priority:** High  
> **Filter type:** `IP_ACCESS_CONTROL`  
> **Dependencies:** GF-02 (Unified Error Response Builder)

---

## Problem Statement

There is no built-in filter for blocking or allowing requests by client IP address. Enterprise deployments require IP-based access control as the first line of defense — before authentication, rate limiting, or any other filter. Currently, operators must implement this externally (e.g., via cloud load balancer rules or firewall), which bypasses the Routify audit trail.

## Solution Overview

A high-priority, order-first filter (`order=-1500`) that evaluates the client IP against configurable allowlists and denylists. Supports individual IPs, CIDR ranges (IPv4 and IPv6), and `X-Forwarded-For` header parsing.

---

## Detailed Implementation Steps

### Step 1: Create Filter Factory

**Files to create:**
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/security/IpAccessControlGatewayFilterFactory.java`
- `routify-api-gateway/src/main/java/io/routify/gateway/filter/security/CidrMatcher.java`

**Config params:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `mode` | `ALLOWLIST` \| `DENYLIST` | `DENYLIST` | Whether the list is an allowlist or denylist |
| `addresses` | `String[]` | `[]` | IP addresses and CIDR ranges |
| `trustProxy` | `boolean` | `true` | Resolve client IP from `X-Forwarded-For` |
| `proxyDepth` | `int` | `1` | Which `X-Forwarded-For` entry to use |
| `rejectStatus` | `int` | `403` | HTTP status code for rejected requests |
| `rejectMessage` | `String` | `"Access denied"` | Error detail message |

**Implementation:**
1. Implement `Ordered` interface with `order=-1500` (before all auth filters at `-1000`).
2. Compile CIDR ranges once at config bind time using `CidrMatcher`.
3. Resolve client IP: if `trustProxy=true`, parse `X-Forwarded-For` and use the entry at `proxyDepth` from the right.
4. In `DENYLIST` mode: block if IP matches any entry.
5. In `ALLOWLIST` mode: block if IP does NOT match any entry.
6. Use `GatewayProblemResponse` (from GF-02) for rejection responses.

**Task list:**
- [x] Create `IpAccessControlGatewayFilterFactory` with `Ordered` interface
- [x] Create `CidrMatcher` utility for CIDR range matching
- [x] Implement `X-Forwarded-For` parsing with configurable proxy depth
- [x] Implement `ALLOWLIST` and `DENYLIST` modes
- [x] Use `GatewayProblemResponse` for rejections

---

### Step 2: IPv6 Support

**Implementation:**
- Parse both IPv4 (`192.168.1.0/24`) and IPv6 (`2001:db8::/32`) CIDR ranges.
- Normalize IPv4-mapped IPv6 addresses (`::ffff:192.168.1.1` → `192.168.1.1`) for consistent matching.
- Use `java.net.InetAddress` for parsing and `CidrMatcher` for prefix matching.

**Task list:**
- [x] Support IPv4 CIDR ranges
- [x] Support IPv6 CIDR ranges
- [x] Normalize IPv4-mapped IPv6 addresses

---

### Step 3: `FilterType` Enum Addition

**Files to modify:**
- `routify-common/.../domain/FilterType.java` — add `IP_ACCESS_CONTROL`
- `routify-dashboard/src/types/index.ts` — add to `FilterType` union

**Task list:**
- [x] Add `IP_ACCESS_CONTROL` to `FilterType` enum
- [x] Add to TypeScript `FilterType` union
- [x] Add filter config form in dashboard `src/modules/filters/`

---

### Step 4: Metrics

**Metrics to register:**
- `routify.filter.ip_access_control.allowed` — counter, tagged by `routeId`
- `routify.filter.ip_access_control.blocked` — counter, tagged by `routeId`

**Task list:**
- [x] Register Micrometer counters
- [x] Increment on allow/block decisions

---

### Step 5: Tests

**Files to create:**
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/security/IpAccessControlGatewayFilterFactoryTest.java`
- `routify-api-gateway/src/test/java/io/routify/gateway/filter/security/CidrMatcherTest.java`

**Test cases:**
- Denylist blocks matching IP
- Denylist allows non-matching IP
- Allowlist allows matching IP
- Allowlist blocks non-matching IP
- CIDR range `10.0.0.0/8` matches `10.1.2.3`
- IPv6 CIDR range matches correctly
- IPv4-mapped IPv6 normalized for matching
- `X-Forwarded-For` parsing with different proxy depths
- Empty `addresses` list → allow all (denylist) / block all (allowlist)
- Hot-reload updates CIDR list without restart

**Task list:**
- [x] Write `CidrMatcher` unit tests
- [x] Write filter factory tests for both modes
- [x] Write `X-Forwarded-For` parsing tests
- [x] Write IPv6 tests

---

## Acceptance Criteria

- [x] Filter blocks/allows by IP and CIDR range
- [x] IPv4 and IPv6 fully supported
- [x] `X-Forwarded-For` parsing with configurable proxy depth
- [x] CIDR ranges compiled once at config time (not per-request)
- [x] Hot-reload via Kafka event pipeline
- [x] Metrics for allowed/blocked counts

