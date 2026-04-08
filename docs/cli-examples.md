# Routify CLI Examples — Route Import / Export

> These examples use `curl` to interact with the Routify admin-api.
> You can use either a **JWT token** (from dashboard login) or an **API key** (from the API Keys module) for authentication.

---

## Authentication

### With JWT Token

```bash
# Login to get a JWT token
TOKEN=$(curl -s -X POST http://localhost:8082/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"changeme","tenantSlug":"default"}' \
  | jq -r '.accessToken')
```

### With API Key (recommended for CI/CD pipelines)

```bash
# Use an API key created via the dashboard or API
API_KEY="rk_your_api_key_here"
```

---

## Export Configuration

### Export as YAML (default)

```bash
# Export all routes, filters, and gateway config for a tenant
curl -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     "http://localhost:8082/api/v1/admin/routes/export?format=yaml" \
     -o routify-export.yaml
```

### Export as JSON

```bash
curl -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     "http://localhost:8082/api/v1/admin/routes/export?format=json" \
     -o routify-export.json
```

### Export with environment filter

```bash
# Export only PRODUCTION routes
curl -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     "http://localhost:8082/api/v1/admin/routes/export?format=yaml&environment=PRODUCTION" \
     -o routify-production.yaml

# Export only STAGING routes
curl -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     "http://localhost:8082/api/v1/admin/routes/export?format=yaml&environment=STAGING" \
     -o routify-staging.yaml
```

### Export with API key authentication

```bash
curl -H "X-Api-Key: $API_KEY" \
     -H "X-Tenant-Id: $TENANT_ID" \
     "http://localhost:8082/api/v1/admin/routes/export?format=yaml" \
     -o routify-export.yaml
```

---

## Preview Import (Dry Run)

Preview shows what changes would be applied **without** actually making them.

```bash
curl -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     -H "Content-Type: application/x-yaml" \
     --data-binary @routify-export.yaml \
     "http://localhost:8082/api/v1/admin/routes/import/preview"
```

### Example response

```json
{
  "valid": true,
  "changes": {
    "filters": {
      "create": [{ "name": "new-rate-limit", "type": "RATE_LIMIT_FIXED_WINDOW" }],
      "update": [{ "name": "jwt-auth-global", "changes": ["config.issuer"] }],
      "unchanged": ["correlation-id"],
      "delete": []
    },
    "routes": {
      "create": [{ "name": "payments-api", "type": "/api/payments/**" }],
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

---

## Apply Import

Apply executes the import, dispatching Kafka commands for each change.
Returns HTTP 202 (Accepted) — changes are applied asynchronously.

```bash
curl -X POST \
     -H "Authorization: Bearer $TOKEN" \
     -H "X-Tenant-Id: $TENANT_ID" \
     -H "Content-Type: application/x-yaml" \
     --data-binary @routify-export.yaml \
     "http://localhost:8082/api/v1/admin/routes/import"
```

### Example response

```json
{
  "status": "accepted",
  "message": "Import in progress — filters: 2 created, 1 updated; routes: 3 created, 0 updated"
}
```

---

## CI/CD Pipeline Example

A typical GitOps workflow using Routify import/export:

```bash
#!/bin/bash
# deploy-routes.sh — Apply route configuration from Git

set -euo pipefail

API_URL="${ROUTIFY_API_URL:-http://localhost:8082}"
TENANT_ID="${ROUTIFY_TENANT_ID}"
API_KEY="${ROUTIFY_API_KEY}"
CONFIG_FILE="${1:-routify-export.yaml}"

echo "=== Previewing import ==="
PREVIEW=$(curl -sf -X POST \
  -H "X-Api-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Content-Type: application/x-yaml" \
  --data-binary @"$CONFIG_FILE" \
  "$API_URL/api/v1/admin/routes/import/preview")

echo "$PREVIEW" | jq .

# Check if there are changes
TOTAL_CHANGES=$(echo "$PREVIEW" | jq '
  .changes.filters.create | length +
  (.changes.filters.update | length) +
  (.changes.routes.create | length) +
  (.changes.routes.update | length)')

if [ "$TOTAL_CHANGES" -eq 0 ]; then
  echo "No changes to apply."
  exit 0
fi

echo "=== Applying $TOTAL_CHANGES changes ==="
curl -sf -X POST \
  -H "X-Api-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Content-Type: application/x-yaml" \
  --data-binary @"$CONFIG_FILE" \
  "$API_URL/api/v1/admin/routes/import" | jq .

echo "=== Import submitted ==="
```

---

## Notes

- **Idempotency**: Importing the same file twice is safe — duplicate commands are deduplicated by the route-service.
- **Import is additive**: Resources in the database but not in the import file are NOT deleted.
- **Gateway config**: If the export includes a `gatewayConfig` section, it will be applied on import.
- **Permissions**: Export requires `ROUTES_READ`; Import requires `ROUTES_WRITE`. Both require `SUPER_ADMIN` or `TENANT_ADMIN` role.

