package io.routify.identity.service;

import io.routify.common.domain.Permission;
import io.routify.common.domain.UserRole;
import io.routify.common.exception.RoutifyException;
import io.routify.identity.domain.RoleDefinition;
import io.routify.identity.repository.RoleDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing role definitions and resolving permissions.
 *
 * <p>Built-in roles are immutable (permissions can only be adjusted by SUPER_ADMIN).
 * Custom roles are tenant-scoped and created by TENANT_ADMIN+.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleDefinitionRepository roleRepository;

    // ─── Queries ──────────────────────────────────────────────────────────────

    public List<RoleDefinition> findAllForTenant(UUID tenantId) {
        return roleRepository.findAllForTenant(tenantId);
    }

    public Page<RoleDefinition> findAllForTenant(UUID tenantId, Pageable pageable) {
        return roleRepository.findAllForTenant(tenantId, pageable);
    }

    public RoleDefinition findById(UUID id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new RoutifyException.NotFound("Role", id.toString()));
    }

    /**
     * Resolves the permission set for a user's role within a tenant context.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Look up the built-in {@link RoleDefinition} by role name.</li>
     *   <li>Return its permission set as a list of permission code strings.</li>
     * </ol>
     *
     * @param role     the user's role enum value
     * @param tenantId the tenant context (for future custom-role-per-user support)
     * @return list of permission code strings (e.g. {@code ["ROUTES_READ", "ROUTES_WRITE"]})
     */
    public List<String> getPermissionsForRole(UserRole role, UUID tenantId) {
        // First try tenant-scoped lookup (for when users have custom role_id)
        // For now, built-in roles are resolved by name
        Optional<RoleDefinition> roleDef = roleRepository.findByNameAndBuiltInTrue(role.name());
        if (roleDef.isPresent()) {
            return new ArrayList<>(roleDef.get().getPermissions());
        }
        // Fallback: return empty — caller should handle gracefully
        log.warn("No role definition found for role={}, tenantId={}", role, tenantId);
        return List.of();
    }

    /**
     * Resolves permissions for a given role definition ID.
     */
    public List<String> getPermissionsForRoleId(UUID roleId) {
        RoleDefinition roleDef = findById(roleId);
        return new ArrayList<>(roleDef.getPermissions());
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    @Transactional
    public RoleDefinition create(UUID tenantId, String name, String description, Set<String> permissions) {
        // Validate: custom roles cannot use built-in role names
        for (UserRole builtIn : UserRole.values()) {
            if (builtIn.name().equalsIgnoreCase(name)) {
                throw new RoutifyException.Conflict("Cannot create custom role with built-in name: " + name);
            }
        }
        // Check uniqueness within tenant
        roleRepository.findByNameForTenant(name, tenantId).ifPresent(existing -> {
            throw new RoutifyException.Conflict("Role '" + name + "' already exists");
        });
        // Validate all permission strings
        validatePermissions(permissions);

        RoleDefinition role = RoleDefinition.builder()
                .tenantId(tenantId)
                .name(name)
                .description(description)
                .builtIn(false)
                .permissions(permissions)
                .build();
        role = roleRepository.save(role);
        log.info("Created custom role '{}' for tenant={} with {} permissions", name, tenantId, permissions.size());
        return role;
    }

    @Transactional
    public RoleDefinition update(UUID id, String name, String description, Set<String> permissions) {
        RoleDefinition role = findById(id);
        if (name != null) {
            // Built-in roles cannot be renamed
            if (role.isBuiltIn()) {
                throw new RoutifyException.Validation("Built-in role names cannot be changed");
            }
            role.setName(name);
        }
        if (description != null) {
            role.setDescription(description);
        }
        if (permissions != null) {
            validatePermissions(permissions);
            role.setPermissions(permissions);
        }
        role = roleRepository.save(role);
        log.info("Updated role '{}' (id={}), permissions={}", role.getName(), id,
                permissions != null ? permissions.size() : "unchanged");
        return role;
    }

    @Transactional
    public void delete(UUID id) {
        RoleDefinition role = findById(id);
        if (role.isBuiltIn()) {
            throw new RoutifyException.Validation("Built-in roles cannot be deleted");
        }
        roleRepository.delete(role);
        log.info("Deleted custom role '{}' (id={})", role.getName(), id);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void validatePermissions(Set<String> permissions) {
        Set<String> validNames = Arrays.stream(Permission.values())
                .map(Permission::name)
                .collect(Collectors.toSet());
        for (String perm : permissions) {
            if (!validNames.contains(perm)) {
                throw new RoutifyException.Validation("Unknown permission: " + perm);
            }
        }
    }
}

