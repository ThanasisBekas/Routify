package gr.routify.route.messaging;

import gr.routify.common.event.CommandEvent;
import gr.routify.common.event.KafkaTopics;
import gr.routify.route.domain.FilterDefinition;
import gr.routify.route.domain.ProcessedCommand;
import gr.routify.route.domain.Route;
import gr.routify.route.repository.ProcessedCommandRepository;
import gr.routify.route.service.FilterDefinitionService;
import gr.routify.route.service.RouteService;
import gr.routify.route.service.RouteUpdateCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka command consumer for routify-route-service.
 *
 * <p>Consumes route and filter {@link CommandEvent}s published by routify-admin-api.
 * Each command is a strongly-typed record — no more {@code Map<String,Object>} parsing.
 * Jackson deserialises the {@code "type"} discriminator into the concrete record type,
 * then Java 21 sealed-class pattern matching dispatches to the right handler.
 *
 * <p><b>Idempotency:</b> Every command's {@code commandId} is checked against the
 * {@code processed_command} table before execution and recorded after success,
 * within the same DB transaction. Duplicate commands are safely skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteCommandKafkaConsumer {

    private final RouteService               routeService;
    private final FilterDefinitionService    filterService;
    private final ProcessedCommandRepository processedCommandRepo;

    @KafkaListener(
            topics = KafkaTopics.ROUTE_COMMANDS,
            groupId = "routify-route-service-commands",
            containerFactory = "routeCommandKafkaListenerContainerFactory"
    )
    public void onRouteCommand(CommandEvent cmd, Acknowledgment ack) {
        if (isDuplicate(cmd)) { ack.acknowledge(); return; }
        try {
            executeRouteCommand(cmd);
            markProcessed(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process route command: type={} — {}", cmd.getClass().getSimpleName(), e.getMessage(), e);
            // Don't ack — let Kafka retry or route to DLQ
        }
    }

    @KafkaListener(
            topics = KafkaTopics.FILTER_COMMANDS,
            groupId = "routify-route-service-filter-commands",
            containerFactory = "routeCommandKafkaListenerContainerFactory"
    )
    public void onFilterCommand(CommandEvent cmd, Acknowledgment ack) {
        if (isDuplicate(cmd)) { ack.acknowledge(); return; }
        try {
            executeFilterCommand(cmd);
            markProcessed(cmd);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process filter command: type={} — {}", cmd.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    // ─── Idempotency helpers ─────────────────────────────────────────────────

    private boolean isDuplicate(CommandEvent cmd) {
        if (cmd.commandId() == null) {
            log.warn("Command has no commandId — skipping idempotency check: type={}", cmd.getClass().getSimpleName());
            return false;
        }
        if (processedCommandRepo.existsById(cmd.commandId())) {
            log.info("Duplicate command skipped: type={} commandId={}", cmd.getClass().getSimpleName(), cmd.commandId());
            return true;
        }
        return false;
    }

    private void markProcessed(CommandEvent cmd) {
        if (cmd.commandId() == null) return;
        try {
            processedCommandRepo.save(new ProcessedCommand(cmd.commandId(), cmd.getClass().getSimpleName()));
        } catch (DataIntegrityViolationException e) {
            // Concurrent consumer already recorded this commandId — safe to ignore
            log.debug("Command already recorded (concurrent duplicate): commandId={}", cmd.commandId());
        }
    }

    // ─── Command dispatchers ─────────────────────────────────────────────────

    private void executeRouteCommand(CommandEvent cmd) {
        log.info("Route command received: type={} tenantId={} by={}",
                cmd.getClass().getSimpleName(), cmd.tenantId(), cmd.requestedBy());

        switch (cmd) {
            case CommandEvent.CreateRoute c -> {
                Route route = Route.builder()
                        .tenantId(c.tenantId())
                        .name(c.name())
                        .description(c.description())
                        .pathPattern(c.pathPattern())
                        .methods(c.methods() != null ? c.methods() : "*")
                        .upstreamUri(c.upstreamUri())
                        .stripPrefix(c.stripPrefix())
                        .createdBy(c.requestedBy())
                        .extraConfig(c.extraConfig())
                        .build();
                routeService.create(route, c.tenantId(), c.requestedBy());
            }
            case CommandEvent.UpdateRoute c -> {
                var update = new RouteUpdateCommand(
                        c.name(), c.description(), c.pathPattern(),
                        c.methods(), c.upstreamUri(), c.stripPrefix(), c.extraConfig());
                routeService.update(c.id(), c.tenantId(), update);
            }
            case CommandEvent.ActivateRoute   c -> routeService.activate(c.id(), c.tenantId());
            case CommandEvent.DeactivateRoute c -> routeService.deactivate(c.id(), c.tenantId());
            case CommandEvent.DeleteRoute     c -> routeService.delete(c.id(), c.tenantId());
            case CommandEvent.AttachFilter    c -> routeService.attachFilter(
                    c.routeId(), c.filterId(), c.order(),
                    c.phase() != null ? c.phase() : "PRE", c.tenantId());
            case CommandEvent.DetachFilter    c -> routeService.detachFilter(
                    c.routeId(), c.filterId(), c.tenantId());
            default -> log.warn("Unexpected command type on route topic: {}",
                    cmd.getClass().getSimpleName());
        }
    }

    private void executeFilterCommand(CommandEvent cmd) {
        log.info("Filter command received: type={} tenantId={} by={}",
                cmd.getClass().getSimpleName(), cmd.tenantId(), cmd.requestedBy());

        switch (cmd) {
            case CommandEvent.CreateFilter c -> {
                FilterDefinition filter = FilterDefinition.builder()
                        .tenantId(c.tenantId())
                        .name(c.name())
                        .description(c.description())
                        .filterType(c.filterType())
                        .config(c.config())
                        .createdBy(c.requestedBy())
                        .gatewayConfigRef(c.gatewayConfigRef())
                        .build();
                filterService.create(filter, c.tenantId());
            }
            case CommandEvent.UpdateFilter c -> {
                FilterDefinition existing = filterService.findById(c.id(), c.tenantId());
                if (c.name()             != null) existing.setName(c.name());
                if (c.description()      != null) existing.setDescription(c.description());
                if (c.config()           != null) existing.setConfig(c.config());
                existing.setGatewayConfigRef(c.gatewayConfigRef());
                filterService.update(c.id(), c.tenantId(), existing);
            }
            case CommandEvent.DeleteFilter c -> filterService.delete(c.id(), c.tenantId());
            default -> log.warn("Unexpected command type on filter topic: {}",
                    cmd.getClass().getSimpleName());
        }
    }
}
