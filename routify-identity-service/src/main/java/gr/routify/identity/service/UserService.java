package gr.routify.identity.service;

import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.exception.RoutifyException;
import gr.routify.identity.domain.AppUser;
import gr.routify.identity.domain.Tenant;
import gr.routify.identity.dto.AuthDto;
import gr.routify.identity.repository.TenantRepository;
import gr.routify.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * User management service — CRUD operations for users within a tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<AppUser> findAll(UUID tenantId, Pageable pageable) {
        if (tenantId == null) {
            // SUPER_ADMIN cross-tenant query: return all non-deleted users across all workspaces
            return userRepository.findAllByStatusNot(AppUser.Status.DELETED, pageable);
        }
        return userRepository.findAllByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public AppUser findById(UUID id, UUID tenantId) {
        return userRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("User", id.toString()));
    }

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

        publishEvent(new DomainEvent.UserCreated(
                UUID.randomUUID(), tenantId, saved.getId(),
                saved.getUsername(), saved.getEmail(), saved.getRole().name(),
                Instant.now(), null, null));

        log.info("User created: id={} username={} tenant={}", saved.getId(), saved.getUsername(), tenantId);
        return saved;
    }

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

        publishEvent(new DomainEvent.UserUpdated(
                UUID.randomUUID(), tenantId, saved.getId(),
                saved.getUsername(), Instant.now(), null, null));

        return saved;
    }

    @Transactional
    public void delete(UUID id, UUID tenantId) {
        AppUser user = findById(id, tenantId);
        user.delete();
        userRepository.save(user);

        publishEvent(new DomainEvent.UserDeleted(
                UUID.randomUUID(), tenantId, id, Instant.now(), null, null));

        log.info("User deleted: id={}", id);
    }

    private void publishEvent(DomainEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.USER_EVENTS, event.tenantId().toString(), json);
        } catch (Exception e) {
            log.error("Failed to publish user event: {}", e.getMessage());
        }
    }
}

