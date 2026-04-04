package gr.routify.identity.service;

import gr.routify.common.domain.TenantPlan;
import gr.routify.common.event.DomainEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.common.exception.RoutifyException;
import gr.routify.identity.domain.Tenant;
import gr.routify.identity.dto.AuthDto;
import gr.routify.identity.outbox.IdentityOutboxEventStore;
import gr.routify.identity.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Tenant management service.
 *
 * <p>Domain events are written to the Transactional Outbox (routify_identity.outbox_event)
 * within the same DB transaction as the entity mutation. The {@link gr.routify.identity.outbox.IdentityOutboxPoller}
 * publishes them to Kafka asynchronously — eliminating the dual-write anti-pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final IdentityOutboxEventStore outboxStore;

    @Transactional(readOnly = true)
    public Page<Tenant> findAll(Pageable pageable) {
        return tenantRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Tenant findById(UUID id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new RoutifyException.NotFound("Tenant", id.toString()));
    }

    @Transactional
    public Tenant create(AuthDto.CreateTenantRequest request) {
        if (tenantRepository.existsByName(request.name())) {
            throw new RoutifyException.Conflict("Tenant name '%s' already exists".formatted(request.name()));
        }
        if (tenantRepository.existsBySlug(request.slug())) {
            throw new RoutifyException.Conflict("Tenant slug '%s' already exists".formatted(request.slug()));
        }

        Tenant tenant = Tenant.builder()
                .name(request.name())
                .slug(request.slug())
                .plan(request.plan())
                .contactEmail(request.contactEmail())
                .build();

        Tenant saved = tenantRepository.save(tenant);

        outboxStore.store(
                new DomainEvent.TenantCreated(
                        UUID.randomUUID(), saved.getId(),
                        saved.getName(), saved.getSlug(), saved.getPlan().name(),
                        Instant.now(), null, null),
                KafkaTopics.TENANT_EVENTS,
                saved.getId());

        log.info("Tenant created: id={} slug={} plan={}", saved.getId(), saved.getSlug(), saved.getPlan());
        return saved;
    }

    @Transactional
    public Tenant update(UUID id, String name, TenantPlan plan, String contactEmail) {
        Tenant tenant = findById(id);

        if (name != null && !name.isBlank() && !name.equals(tenant.getName())) {
            if (tenantRepository.existsByName(name)) {
                throw new RoutifyException.Conflict("Tenant name '%s' already exists".formatted(name));
            }
            tenant.setName(name);
        }
        if (plan != null) {
            tenant.upgradePlan(plan);
        }
        if (contactEmail != null) {
            tenant.setContactEmail(contactEmail);
        }

        Tenant saved = tenantRepository.save(tenant);

        outboxStore.store(
                new DomainEvent.TenantUpdated(
                        UUID.randomUUID(), id, saved.getName(), Instant.now(), null, null),
                KafkaTopics.TENANT_EVENTS,
                id);

        log.info("Tenant updated: id={} name={} plan={}", saved.getId(), saved.getName(), saved.getPlan());
        return saved;
    }

    @Transactional
    public Tenant suspend(UUID id, String reason) {
        Tenant tenant = findById(id);
        tenant.suspend();
        Tenant saved = tenantRepository.save(tenant);

        outboxStore.store(
                new DomainEvent.TenantSuspended(
                        UUID.randomUUID(), id, reason, Instant.now(), null, null),
                KafkaTopics.TENANT_EVENTS,
                id);

        log.info("Tenant suspended: id={}", id);
        return saved;
    }

    @Transactional
    public Tenant reactivate(UUID id) {
        Tenant tenant = findById(id);
        tenant.reactivate();
        Tenant saved = tenantRepository.save(tenant);

        outboxStore.store(
                new DomainEvent.TenantUpdated(
                        UUID.randomUUID(), id, saved.getName(), Instant.now(), null, null),
                KafkaTopics.TENANT_EVENTS,
                id);

        return saved;
    }

}

