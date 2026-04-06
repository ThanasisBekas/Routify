package io.routify.identity.service;

import io.routify.common.event.DomainEvent;
import io.routify.common.event.KafkaTopics;
import io.routify.common.exception.RoutifyException;
import io.routify.identity.config.CacheConfig;
import io.routify.identity.domain.AppUser;
import io.routify.identity.domain.Tenant;
import io.routify.identity.dto.AuthDto;
import io.routify.identity.outbox.IdentityOutboxEventStore;
import io.routify.identity.outbox.IdentityOutboxPoller;
import io.routify.identity.repository.TenantRepository;
import io.routify.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * User management service — CRUD operations for users within a tenant.
 *
 * <p>Domain events are written to the Transactional Outbox within the same DB
 * transaction as the entity mutation. The {@link IdentityOutboxPoller} publishes
 * them to Kafka asynchronously — eliminating the dual-write anti-pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityOutboxEventStore outboxStore;

    @Cacheable(value = CacheConfig.CACHE_USERS, key = "'list:' + #tenantId + ':' + #pageable.pageNumber + ':' + #pageable.pageSize")
    @Transactional(readOnly = true)
    public Page<AppUser> findAll(UUID tenantId, Pageable pageable) {
        if (tenantId == null) {
            // SUPER_ADMIN cross-tenant query: return all non-deleted users across all workspaces
            return userRepository.findAllByStatusNot(AppUser.Status.DELETED, pageable);
        }
        return userRepository.findAllByTenantId(tenantId, pageable);
    }

    @Cacheable(value = CacheConfig.CACHE_USERS, key = "#id")
    @Transactional(readOnly = true)
    public AppUser findById(UUID id, UUID tenantId) {
        return userRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("User", id.toString()));
    }

    @CacheEvict(value = CacheConfig.CACHE_USERS, allEntries = true)
    @Transactional
    public AppUser create(AuthDto.CreateUserRequest request, UUID tenantId) {
        // Validate tenant exists
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("Tenant", tenantId.toString()));

        if (!tenant.isActive()) {
            throw new RoutifyException.Forbidden("Tenant is suspended");
        }

        if (userRepository.existsByUsernameAndTenantId(request.username(), tenantId)) {
            throw new RoutifyException.Conflict("Username '%s' already exists".formatted(request.username()));
        }
        if (userRepository.existsByEmailAndTenantId(request.email(), tenantId)) {
            throw new RoutifyException.Conflict("Email '%s' already exists".formatted(request.email()));
        }

        AppUser user = AppUser.builder()
                .tenantId(tenantId)
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(request.role())
                .build();

        AppUser saved = userRepository.save(user);

        outboxStore.store(
                new DomainEvent.UserCreated(
                        UUID.randomUUID(), tenantId, saved.getId(),
                        saved.getUsername(), saved.getEmail(), saved.getRole().name(),
                        Instant.now(), null, null),
                KafkaTopics.USER_EVENTS,
                tenantId);

        log.info("User created: id={} username={} tenant={}", saved.getId(), saved.getUsername(), tenantId);
        return saved;
    }

    @CacheEvict(value = CacheConfig.CACHE_USERS, allEntries = true)
    @Transactional
    public AppUser update(UUID id, UUID tenantId, AuthDto.UpdateUserRequest request) {
        AppUser user = findById(id, tenantId);

        if (request.username() != null && !request.username().equals(user.getUsername())) {
            if (userRepository.existsByUsernameAndTenantId(request.username(), tenantId)) {
                throw new RoutifyException.Conflict("Username '%s' already exists".formatted(request.username()));
            }
            user.setUsername(request.username());
        }
        if (request.email() != null && !request.email().equals(user.getEmail())) {
            if (userRepository.existsByEmailAndTenantId(request.email(), tenantId)) {
                throw new RoutifyException.Conflict("Email '%s' already exists".formatted(request.email()));
            }
            user.setEmail(request.email());
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }

        AppUser saved = userRepository.save(user);

        outboxStore.store(
                new DomainEvent.UserUpdated(
                        UUID.randomUUID(), tenantId, saved.getId(),
                        saved.getUsername(), Instant.now(), null, null),
                KafkaTopics.USER_EVENTS,
                tenantId);

        return saved;
    }

    @CacheEvict(value = CacheConfig.CACHE_USERS, allEntries = true)
    @Transactional
    public void delete(UUID id, UUID tenantId) {
        AppUser user = findById(id, tenantId);
        user.delete();
        userRepository.save(user);

        outboxStore.store(
                new DomainEvent.UserDeleted(
                        UUID.randomUUID(), tenantId, id, Instant.now(), null, null),
                KafkaTopics.USER_EVENTS,
                tenantId);

        log.info("User deleted: id={}", id);
    }
}

