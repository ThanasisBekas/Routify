package gr.routify.gateway.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Listens for successful gateway route refreshes and logs added/removed route IDs
 * by diffing the previous and current route sets.
 */
@Slf4j
@Component
public class RouteRefreshAuditLogger {

    private final RouteDefinitionLocator routeDefinitionLocator;
    private final Set<String> previousRouteIds = ConcurrentHashMap.newKeySet();

    public RouteRefreshAuditLogger(RouteDefinitionLocator routeDefinitionLocator) {
        this.routeDefinitionLocator = routeDefinitionLocator;
    }

    @EventListener
    public void onRoutesRefreshed(RefreshRoutesResultEvent event) {
        if (!event.isSuccess()) {
            log.error("Route refresh failed: {}", event.getThrowable().getMessage(),
                    event.getThrowable());
            return;
        }

        routeDefinitionLocator.getRouteDefinitions()
                .map(RouteDefinition::getId)
                .collect(Collectors.toUnmodifiableSet())
                .doOnNext(this::auditRouteChanges)
                .subscribe(
                        currentIds -> {
                            previousRouteIds.clear();
                            previousRouteIds.addAll(currentIds);
                        },
                        error -> log.error("Failed to query route definitions for audit logging", error)
                );
    }

    private void auditRouteChanges(Set<String> currentRouteIds) {
        Set<String> added = currentRouteIds.stream()
                .filter(id -> !previousRouteIds.contains(id))
                .collect(Collectors.toUnmodifiableSet());

        Set<String> removed = previousRouteIds.stream()
                .filter(id -> !currentRouteIds.contains(id))
                .collect(Collectors.toUnmodifiableSet());

        if (previousRouteIds.isEmpty()) {
            log.info("Initial route load: {} routes configured — [{}]",
                    currentRouteIds.size(), String.join(", ", currentRouteIds));
        } else if (added.isEmpty() && removed.isEmpty()) {
            log.info("Route refresh completed: {} routes unchanged", currentRouteIds.size());
        } else {
            if (!added.isEmpty()) {
                log.info("Route refresh — ADDED {} route(s): [{}]",
                        added.size(), String.join(", ", added));
            }
            if (!removed.isEmpty()) {
                log.warn("Route refresh — REMOVED {} route(s): [{}]",
                        removed.size(), String.join(", ", removed));
            }
            log.info("Route refresh summary: {} total routes (was {})",
                    currentRouteIds.size(), previousRouteIds.size());
        }
    }
}

