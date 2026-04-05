# Initiative 07 — Route Import / Export & GitOps Foundation

> **Parent:** [Q3 2026 Roadmap](../Q3-2026-ROADMAP.md) · **Timeline:** Weeks 8–11 · **Owner:** Admin-API + Route-service teams

---

## Problem Statement

Gateway configuration (routes + filters + gateway config) exists only in the database. There is no way to version-control it, diff changes across environments, or restore configuration after a disaster. Environment parity between dev/staging/production requires manual recreation of routes. CI pipelines cannot declaratively apply route changes.

## Solution Overview

Add YAML-based export and import of the complete gateway configuration through the admin-api. The export produces a self-contained, human-readable YAML document. The import validates the document, computes a diff against current state, and applies changes via the existing Kafka command pipeline. This establishes the foundation for a future GitOps reconciliation loop.

---

## Detailed Implementation Steps

### Step 1: Export Schema Definition

**File to create:**
- `docs/schema/route-export-v1.yaml` — the schema specification

**Schema (v1):**
```yaml
apiVersion: routify/v1
kind: GatewayConfiguration
metadata:
  exportedAt: "2026-06-15T10:30:00Z"
  exportedBy: "admin@routify.io"
  tenantId: "550e8400-e29b-41d4-a716-446655440000"
  environment: PRODUCTION        # optional, from Initiative 02

filters:
  - name: "jwt-auth-global"
    filterType: AUTH_JWT
    description: "Global JWT authentication"
    enabled: true
    config:
      issuer: "https://auth.example.com"
      audience: "routify-api"
      publicKey: "***MASKED***"  # sensitive fields masked on export

routes:
  - name: "orders-api"
    pathPattern: "/api/orders/**"
    methods: "GET,POST,PUT,DELETE"
    upstreamUri: "http://orders-service:8080"
    stripPrefix: "/api"
    status: ACTIVE
    description: "Order management API"
    filters:
      - filterName: "jwt-auth-global"
        order: 1
        phase: PRE
        enabled: true
      - filterName: "rate-limit-100rpm"
        order: 2
        phase: PRE
        enabled: true
    extraConfig:
      timeout: 30000

gatewayConfig:               # optional section
  cors:
    enabled: true
    allowedOriginPatterns: ["https://*.example.com"]
    # ...
  securityHeaders:
    enabled: true
    # ...
```

**Design decisions:**
- Filters are referenced by **name** (natural key), not UUID.
- Routes reference filters by filter **name**, not ID.
- Sensitive config values are masked on export (`@SensitiveField` fields become `***MASKED***`).
- Import skips masked values (does not overwrite stored secrets — uses `Sensitive.isMasked()`).
- `apiVersion` enables future schema evolution.

**Task list:**
- [ ] Define and document the YAML schema
- [ ] Create Java records for serialization/deserialization

---

### Step 2: Export DTO Model (routify-common)

**Files to create:**
- `routify-common/.../dto/export/GatewayExportV1.java`

```java
public record GatewayExportV1(
    String apiVersion,       // "routify/v1"
    String kind,             // "GatewayConfiguration"
    ExportMetadata metadata,
    List<FilterExportEntry> filters,
    List<RouteExportEntry> routes,
    Map<String, Object> gatewayConfig  // optional, raw JSON structure
) {
    public record ExportMetadata(Instant exportedAt, String exportedBy,
        UUID tenantId, String environment) {}
    public record FilterExportEntry(String name, FilterType filterType,
        String description, boolean enabled, Map<String, Object> config) {}
    public record RouteExportEntry(String name, String pathPattern, String methods,
        String upstreamUri, String stripPrefix, RouteStatus status,
        String description, List<RouteFilterRefExport> filters,
        Map<String, Object> extraConfig) {}
    public record RouteFilterRefExport(String filterName, int order,
        String phase, boolean enabled) {}
}
```

**Task list:**
- [ ] Create export DTO records
- [ ] Add SnakeYAML serialization utilities

---

### Step 3: Export Endpoint (admin-api)

**Files to create:**
- `routify-admin-api/.../controller/AdminExportController.java`
- `routify-admin-api/.../service/ExportService.java`

**Endpoint:**
```
GET /api/v1/admin/routes/export?format=yaml&environment=PRODUCTION
Accept: application/x-yaml | application/json
Authorization: SUPER_ADMIN, TENANT_ADMIN
```

**Logic:**
1. Query route-service via RabbitMQ for full route snapshot (reuse `QUEUE_ROUTE_GATEWAY_SNAPSHOT`).
2. Query route-service for all filter definitions.
3. Query route-service for gateway config sections.
4. Map to `GatewayExportV1` DTO.
5. Apply `Sensitive.maskFields()` to filter configs.
6. Serialize to YAML (SnakeYAML `DumperOptions` with block style).
7. Return with `Content-Disposition: attachment; filename="routify-export-{tenant}-{date}.yaml"`.

**Task list:**
- [ ] Create `ExportService`
- [ ] Create `AdminExportController`
- [ ] Add SnakeYAML explicit dependency to `routify-admin-api/pom.xml`
- [ ] Implement YAML serialization with block-style formatting
- [ ] Apply sensitive field masking
- [ ] Add JSON format option (via `Accept` header or query param)

---

### Step 4: Import Preview Endpoint (admin-api)

**File to create/modify:**
- `routify-admin-api/.../controller/AdminImportController.java`
- `routify-admin-api/.../service/ImportService.java`

**Endpoint:**
```
POST /api/v1/admin/routes/import/preview
Content-Type: application/x-yaml
Authorization: SUPER_ADMIN, TENANT_ADMIN
```

**Response:**
```json
{
  "valid": true,
  "changes": {
    "filters": {
      "create": [{ "name": "new-rate-limit", "filterType": "RATE_LIMIT_FIXED_WINDOW" }],
      "update": [{ "name": "jwt-auth-global", "changes": ["config.issuer"] }],
      "unchanged": ["correlation-id"],
      "delete": []
    },
    "routes": {
      "create": [{ "name": "payments-api", "pathPattern": "/api/payments/**" }],
      "update": [{ "name": "orders-api", "changes": ["upstreamUri", "filters"] }],
      "unchanged": ["health-check"],
      "delete": []
    }
  },
  "warnings": [
    "Filter 'jwt-auth-global' has masked config fields that will not be overwritten"
  ]
}
```

**Diff algorithm:**
1. Parse YAML → `GatewayExportV1`.
2. Validate `apiVersion`, required fields, filter type validity.
3. Load current state from route-service (RabbitMQ queries).
4. Match by **name** (natural key):
   - Present in import but not in DB → `create`
   - Present in both → compare fields → `update` (list changed fields) or `unchanged`
   - Present in DB but not in import → not deleted (import is additive by default)
5. Skip masked config values in comparison.

**Task list:**
- [ ] Create `ImportService` with diff logic
- [ ] Create YAML deserialization (SnakeYAML → `GatewayExportV1`)
- [ ] Implement field-level diff comparison
- [ ] Add validation (schema version, filter types, required fields)
- [ ] Create preview endpoint

---

### Step 5: Import Apply Endpoint (admin-api)

**Endpoint:**
```
POST /api/v1/admin/routes/import
Content-Type: application/x-yaml
Authorization: SUPER_ADMIN, TENANT_ADMIN
Response: 202 AsyncAcknowledgement
```

**Logic:**
1. Run preview logic to compute diff.
2. For each filter to create → publish `CommandEvent.CreateFilter` to `FILTER_COMMANDS`.
3. For each filter to update → publish `CommandEvent.UpdateFilter`.
4. For each route to create → publish `CommandEvent.CreateRoute`.
5. For each route to update → publish `CommandEvent.UpdateRoute`.
6. For each route's filter attachments → publish `CommandEvent.AttachFilter`.
7. If gateway config section present → publish via `QUEUE_GATEWAY_CONFIG_SAVE`.
8. Return 202 with summary: `{ "filtersCreated": 2, "routesUpdated": 1, "total": 5 }`.

**Idempotency:**
- Each command gets a unique `commandId` derived from `SHA256(tenantId + resourceName + fieldHash)`.
- Route-service deduplicates by `commandId` (existing behavior).

**Task list:**
- [ ] Create apply endpoint
- [ ] Generate Kafka commands from diff
- [ ] Implement idempotent commandId generation
- [ ] Add audit event for import action (with filename, change summary)

---

### Step 6: Dashboard UI

**Files to create:**
- `routify-dashboard/src/modules/routes/ImportExportButtons.tsx`
- `routify-dashboard/src/modules/routes/ImportPreviewModal.tsx`

**Export UX:**
- "Export" dropdown button in routes page header: "Export as YAML" / "Export as JSON".
- Downloads file directly.

**Import UX:**
- "Import" button opens modal with file drop zone (accepts `.yaml`, `.yml`, `.json`).
- On file drop: calls preview endpoint, displays diff table:
  - Green rows: new resources
  - Yellow rows: updated resources (with changed fields listed)
  - Gray rows: unchanged
  - Warning banner for masked fields
- "Apply" button sends the import request. Progress toast via SSE/WebSocket events.

**Task list:**
- [ ] Create export button component
- [ ] Create import modal with file drop zone
- [ ] Create diff preview table component
- [ ] Wire to API endpoints
- [ ] Add success/error toasts
- [ ] Add MSW mock handlers

---

### Step 7: CLI Documentation

**File to create:**
- `docs/cli-examples.md`

```bash
# Export all routes for a tenant
curl -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     "http://localhost:8082/api/v1/admin/routes/export?format=yaml" \
     -o routify-export.yaml

# Preview import
curl -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     -H "Content-Type: application/x-yaml" \
     --data-binary @routify-export.yaml \
     "http://localhost:8082/api/v1/admin/routes/import/preview"

# Apply import
curl -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     -H "Content-Type: application/x-yaml" \
     --data-binary @routify-export.yaml \
     "http://localhost:8082/api/v1/admin/routes/import"
```

**Task list:**
- [ ] Write CLI documentation with examples
- [ ] Document API key auth for CI pipelines (depends on Initiative 01)

---

## Acceptance Criteria

- [ ] Export produces a valid YAML file containing all routes, filters, and gateway config
- [ ] Sensitive fields are masked in the export (`***MASKED***`)
- [ ] Import preview shows correct diff (create/update/unchanged) without applying changes
- [ ] Import apply creates/updates resources via existing Kafka command pipeline
- [ ] Importing the same file twice is idempotent (no duplicate resources)
- [ ] Export → Import round-trip preserves all non-sensitive configuration
- [ ] CLI examples work with `curl` and an API key

---

## Future: GitOps Reconciliation (Post-Q3)

The import/export foundation enables a future GitOps reconciliation service that:
1. Watches a Git repository for `routify-export.yaml` changes.
2. On merge to `main`, calls the import endpoint to apply changes.
3. Runs the preview first to validate, then applies.
4. Reports reconciliation results back via webhook (Initiative 03).

This is explicitly out of scope for Q3 but the schema and API design accommodate it.

