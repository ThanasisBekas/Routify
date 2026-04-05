package io.routify.route.dto;

import io.routify.common.domain.RouteStatus;
import io.routify.route.repository.RouteRepository;

/**
 * Typed JPA projection for route status counts.
 * Phase 4.1 fix: replaces untyped {@code Object[]} return from
 * {@link RouteRepository#countByStatusForTenant}.
 */
public record RouteStatusCount(RouteStatus status, Long count) {}

