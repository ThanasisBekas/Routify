# Initiative 04 — Granular RBAC & Permissions

> **Parent:** [Q3 2026 Roadmap](../Q3-2026-ROADMAP.md) · **Timeline:** Weeks 4–7 · **Owner:** Identity-service + All services

---

## Problem Statement

The current 4-role model (`SUPER_ADMIN`, `TENANT_ADMIN`, `OPERATOR`, `VIEWER`) is coarse. An `OPERATOR` who should only manage routes can also view certificates and audit logs. There's no way to grant "can activate routes but not delete them" or "can manage certs but not users". Organizations with compliance requirements need granular, auditable permission assignments.

## Solution Overview

Introduce a **permission-based authorization model** layered on top of existing roles. Each built-in role has a default permission set. Tenant admins can create **custom roles** with arbitrary permission combinations. Permissions are embedded in JWT claims for zero-latency enforcement at each service.

```
Built-in Role          Default Permissions
─────────────────      ─────────────────────────────────────────
SUPER_ADMIN            ALL (*)
TENANT_ADMIN           routes:*, filters:*, users:*, certs:*, audit:read, gateway:config, ...
OPERATOR               routes:read, routes:activate, filters:read
VIEWER                 routes:read, filters:read, audit:read
                       ↑ customizable per tenant
```

---

## Detailed Implementation Steps

### Step 1: Permission Catalog (routify-common)

**File to create:**
- `routify-common/.../domain/Permission.java`

```java
public enum Permission {
    // Routes
    ROUTES_READ, ROUTES_WRITE, ROUTES_ACTIVATE, ROUTES_DELETE, ROUTES_PROMOTE,
    // Filters
    FILTERS_READ, FILTERS_WRITE, FILTERS_DELETE,
    // Users
    USERS_READ, USERS_WRITE, USERS_DELETE,
    // Certificates
    CERTS_READ, CERTS_WRITE, CERTS_ADMIN,
    // Audit
    AUDIT_READ, AUDIT_REPLAY,
    // Gateway config
    GATEWAY_CONFIG_READ, GATEWAY_CONFIG_WRITE,
    // API keys
    API_KEYS_READ, API_KEYS_ADMIN,
    // Webhooks
    WEBHOOKS_READ, WEBHOOKS_ADMIN,
    // AI
    AI_POLICY_READ, AI_POLICY_WRITE,
    // Tenants (SUPER_ADMIN only)
    TENANTS_READ, TENANTS_WRITE, TENANTS_SUSPEND
}
```

**Task list:**
- [ ] Create `Permission` enum
- [ ] Add TypeScript mirror type to `src/types/index.ts`

---

### Step 2: Role-Permission Mapping (identity-service)

**Files to create:**
- `routify-identity-service/src/main/resources/db/migration/V{next}__role_permissions.sql`
- `routify-identity-service/.../domain/RoleDefinition.java`
- `routify-identity-service/.../domain/RolePermission.java`
- `routify-identity-service/.../repository/RoleDefinitionRepository.java`

**Schema:**
```sql
CREATE TABLE routify_identity.role_definition (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID,           -- NULL for built-in roles
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    built_in    BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE routify_identity.role_permission (
    role_id     UUID NOT NULL REFERENCES routify_identity.role_definition(id) ON DELETE CASCADE,
    permission  VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

-- Seed built-in roles
INSERT INTO routify_identity.role_definition (id, name, built_in, description) VALUES
    ('00000000-0000-0000-0000-000000000001', 'SUPER_ADMIN', true, 'Full platform access'),
    ('00000000-0000-0000-0000-000000000002', 'TENANT_ADMIN', true, 'Full tenant access'),
    ('00000000-0000-0000-0000-000000000003', 'OPERATOR', true, 'Route operations'),
    ('00000000-0000-0000-0000-000000000004', 'VIEWER', true, 'Read-only access');

-- SUPER_ADMIN gets all permissions (seeded by DataSeeder or migration)
-- TENANT_ADMIN gets all except TENANTS_*
-- OPERATOR gets ROUTES_READ, ROUTES_ACTIVATE, FILTERS_READ
-- VIEWER gets *_READ permissions only
```

**Task list:**
- [ ] Create Flyway migration with schema + seed data
- [ ] Create `RoleDefinition` entity
- [ ] Create `RolePermission` entity (or use `@ElementCollection`)
- [ ] Create repository
- [ ] Create `RoleService` for CRUD operations

---

### Step 3: JWT Claims Enrichment (identity-service)

**Files to modify:**
- `routify-identity-service/.../security/JwtService.java`

**Changes:**
- On token issuance, resolve user's role → `RoleDefinition` → permission set.
- Add `permissions` claim as a JSON array of permission strings.
- To keep JWT compact, use short permission codes (`r:r` for `ROUTES_READ`) with a mapping. Or, if the full list is < 1KB, use full names for clarity.

```java
// In JwtService.generateAccessToken():
List<String> permissions = roleService.getPermissionsForRole(user.getRole(), user.getTenantId());
claims.put("permissions", permissions);
```

**Feature flag:** `routify.rbac.granular-enabled: false` (default). When false, JWT includes no `permissions` claim and all services fall back to role-based checks.

**Task list:**
- [ ] Resolve permissions in `JwtService`
- [ ] Add `permissions` claim to JWT
- [ ] Add feature flag config property
- [ ] Update `SecurityContext` record in `routify-common` to carry permissions
- [ ] Add `hasPermission(Permission)` helper to `SecurityContext`

---

### Step 4: Service-Side Authorization Updates

**Files to modify across all services:**
- Replace `@PreAuthorize("hasRole('...')")` with `@PreAuthorize("hasAuthority('...')")`.
- When `granular-enabled=false`, authorities are derived from role name (existing behavior).

**Spring Security authority mapping:**
```java
// In JWT filter (each service):
if (permissions != null) {
    authorities = permissions.stream()
        .map(p -> new SimpleGrantedAuthority(p))
        .toList();
} else {
    // Fallback: map role to legacy authorities
    authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
}
```

**Migration strategy** (gradual):
1. Phase A: Add `permissions` claim but keep `hasRole()` checks → no behavioral change.
2. Phase B: Replace `hasRole()` with `hasAuthority()` controller by controller.
3. Phase C: Remove feature flag, permissions are always present.

**Task list:**
- [ ] Update JWT filter in admin-api
- [ ] Update JWT filter in identity-service  
- [ ] Update JWT filter in route-service
- [ ] Update JWT filter in audit-service
- [ ] Update JWT filter in cert-vault
- [ ] Convert `AdminRoutesController` authorization annotations (pilot)
- [ ] Convert remaining controllers after validation

---

### Step 5: Admin-API Role Management Endpoints

**Files to create:**
- `routify-admin-api/.../controller/AdminRolesController.java`

**Endpoints:**
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/admin/roles` | List role definitions (built-in + custom) |
| `GET` | `/api/v1/admin/roles/{id}` | Role detail with permissions |
| `POST` | `/api/v1/admin/roles` | Create custom role (TENANT_ADMIN+) |
| `PUT` | `/api/v1/admin/roles/{id}` | Update role permissions |
| `DELETE` | `/api/v1/admin/roles/{id}` | Delete custom role (not built-in) |
| `GET` | `/api/v1/admin/permissions` | List all available permissions |

**Task list:**
- [ ] Create controller
- [ ] Add RabbitMQ query handlers in identity-service
- [ ] Add Kafka command handlers for role mutations

---

### Step 6: Dashboard Roles UI

**Files to create:**
- `routify-dashboard/src/modules/roles/RolesPage.tsx`
- `routify-dashboard/src/modules/roles/RoleFormModal.tsx`
- `routify-dashboard/src/api/rolesApi.ts`

**UX:**
- List of roles with "Built-in" badge and permission count.
- Role editor: grouped checkboxes by resource type (Routes, Filters, Users, etc.).
- Built-in roles show permissions as read-only (modifiable only by SUPER_ADMIN).
- User create/edit form gains a role dropdown that includes custom roles.

**Task list:**
- [ ] Create `rolesApi.ts`
- [ ] Add TypeScript types (`RoleDefinitionDto`, `PermissionDto`)
- [ ] Create roles list page
- [ ] Create role editor with permission checkboxes
- [ ] Update user form to show custom roles
- [ ] Add route in React Router
- [ ] Add sidebar navigation entry
- [ ] Add MSW mock handlers

---

### Step 7: User Assignment Update

**Files to modify:**
- `routify-identity-service/.../domain/User.java` — change `role` from `UserRole` enum to `UUID roleId` FK.
- Migration: map existing `SUPER_ADMIN/TENANT_ADMIN/OPERATOR/VIEWER` → corresponding built-in `role_definition.id`.

**Backward compatibility:** The `UserRole` enum remains in `routify-common` for display purposes. The `roleId` FK points to `role_definition`, and the response DTO includes both `roleName` and `permissions[]`.

**Task list:**
- [ ] Create Flyway migration to add `role_id` column and backfill
- [ ] Update `User` entity
- [ ] Update user DTOs to include `permissions` list
- [ ] Update dashboard user views

---

## Acceptance Criteria

- [ ] A custom role "CertManager" with only `CERTS_READ` + `CERTS_WRITE` + `CERTS_ADMIN` can be created
- [ ] A user assigned "CertManager" can manage certificates but gets 403 on route operations
- [ ] Built-in roles have sensible default permissions that match current behavior
- [ ] Existing JWTs without `permissions` claim continue to work (role-based fallback)
- [ ] Feature flag allows gradual rollout without breaking existing deployments
- [ ] All permission checks are logged in audit trail for compliance

---

## Rollback Plan

1. Set `routify.rbac.granular-enabled: false` → all services revert to role-based `hasRole()` checks.
2. No schema rollback needed — `role_definition` and `role_permission` tables are additive.
3. JWTs issued with `permissions` claim are still valid (claim is simply ignored when flag is off).

