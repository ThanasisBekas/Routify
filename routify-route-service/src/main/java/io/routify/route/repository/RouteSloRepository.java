package io.routify.route.repository;

import io.routify.route.domain.RouteSlo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteSloRepository extends JpaRepository<RouteSlo, UUID> {

    Optional<RouteSlo> findByRouteId(UUID routeId);
}

