# Initiative Q2-07 — Codebase Cleanup & Deprecated API Removal

> **Parent:** [Q2 2026 Java 25 Roadmap](../Q2-2026-JAVA25-ROADMAP.md) · **Timeline:** Weeks 8–10 · **Owner:** All teams

---

## Problem Statement

The `FilterType` enum carries 12 `@Deprecated` values with no gateway factory implementation, kept only for DB compatibility. `RouteDefinitionBuilder` has skip-warning logic for these values. As we cut a 2.0.0 release, this is the right time to remove them with a proper migration. Additionally, after Initiatives 1–6, some Lombok imports and old patterns remain as artifacts.

---

## Detailed Implementation Steps

### Step 1: Remove Deprecated `FilterType` Values

**12 values to remove:**
`AUTH_NONE`, `RATE_LIMIT_TOKEN_BUCKET`, `PATH_REWRITE`, `PATH_STRIP_PREFIX`, `PATH_ADD_PREFIX`, `QUERY_PARAM_MODIFY`, `BODY_JSONATA_TRANSFORM`, `BODY_SPEL_TRANSFORM`, `VALIDATE_REGEX`, `VALIDATE_SIZE`, `CIRCUIT_BREAKER`, `RETRY`

**Flyway migration** (for each service that has `filter_type` in its schema):
```sql
-- routify-route-service migration
UPDATE routify.filter_definition
SET filter_type = NULL, enabled = false
WHERE filter_type IN ('AUTH_NONE','RATE_LIMIT_TOKEN_BUCKET','PATH_REWRITE',
    'PATH_STRIP_PREFIX','PATH_ADD_PREFIX','QUERY_PARAM_MODIFY',
    'BODY_JSONATA_TRANSFORM','BODY_SPEL_TRANSFORM','VALIDATE_REGEX',
    'VALIDATE_SIZE','CIRCUIT_BREAKER','RETRY');
```

**Java changes:**
- Remove the 12 enum values from `FilterType.java`
- Remove skip-warning logic in `RouteDefinitionBuilder`
- Remove corresponding entries from TypeScript `FilterType` union in `src/types/index.ts`
- Remove any MSW mock data referencing deprecated types
- Remove deprecated filter config constants from `filterConfigConstants.ts`

**Task list:**
- [ ] Create Flyway migration for route-service
- [ ] Remove 12 values from `FilterType` enum
- [ ] Remove skip-warning in `RouteDefinitionBuilder`
- [ ] Update TypeScript `FilterType` union
- [ ] Update dashboard filter registry (`filterRegistry.ts`)
- [ ] Run all tests

---

### Step 2: Lombok Import Cleanup

After Initiative Q2-02 converts DTOs to records, scan for orphaned Lombok imports:

```java
// Remove if no longer used:
import lombok.Data;
import lombok.Value;
import lombok.Getter;
import lombok.Setter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
```

**Keep Lombok on:**
- JPA entities (they need `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`)
- Logging (`@Slf4j`) — this stays everywhere

**Task list:**
- [ ] Run IntelliJ "Optimize Imports" across all modules
- [ ] Verify no compile errors from removed imports
- [ ] Check if any module has zero Lombok usage remaining — remove `lombok` dependency from its POM

---

### Step 3: Dead Code Removal

Run IntelliJ "Unused declaration" inspection:
- Unreachable `private` methods
- Unused `private` fields
- Commented-out code blocks (> 5 lines)
- Unused method parameters (candidate for `_` unnamed variable)

**Task list:**
- [ ] Run inspection on each module
- [ ] Remove confirmed dead code
- [ ] Do NOT remove anything flagged in public APIs (could be used by other modules)

---

### Step 4: `@SuppressWarnings` Audit

Some `@SuppressWarnings` were added for warnings that Java 25 or updated dependencies have resolved:
- `@SuppressWarnings("deprecation")` on `Resilience4JConfig` — verify if still needed
- `@SuppressWarnings("unchecked")` — verify if pattern matching eliminates the need

**Task list:**
- [ ] Review all `@SuppressWarnings` annotations
- [ ] Remove those that are no longer necessary
- [ ] Document remaining suppressions with a comment explaining why

---

### Step 5: Settings Page Java Version Update

**File:** `routify-dashboard/src/modules/settings/SettingsPage.tsx`

The settings page currently shows "Java 21" in the tech stack:
```tsx
{ icon: Cpu, label: 'Java 21', desc: 'Virtual threads, ZGC, pattern matching' },
```

**Update to:**
```tsx
{ icon: Cpu, label: 'Java 25', desc: 'Virtual threads, structured concurrency, scoped values, ZGC' },
```

**Task list:**
- [ ] Update Java version in settings page
- [ ] Update description to reflect Java 25 features

---

## Acceptance Criteria

- [ ] Zero `@Deprecated` values in `FilterType` enum
- [ ] Flyway migration handles existing deprecated filter records in DB
- [ ] Zero orphaned Lombok imports on record-converted classes
- [ ] IntelliJ "Unused declaration" inspection shows zero actionable items
- [ ] Dashboard settings page shows "Java 25"
- [ ] All unit and integration tests pass

