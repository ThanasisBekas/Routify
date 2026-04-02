package gr.routify.route.dto;

import gr.routify.common.domain.RouteStatus;

/**
 * Typed JPA projection for route status counts.
 * Phase 4.1 fix: replaces untyped {@code Object[]} return from
 * {@link gr.routify.route.repository.RouteRepository#countByStatusForTenant}.
 */
public record RouteStatusCount(RouteStatus status, Long count) {}

