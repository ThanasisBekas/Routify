-- V11: Admin stored procedure for manual route deletion.
--
-- Usage:
--   SELECT routify.delete_route('route-uuid-here');
--   SELECT routify.delete_route('route-uuid-here', dry_run := true);   -- preview only
--   SELECT routify.delete_route('route-uuid-here', force := true);     -- skip ACTIVE guard
--
-- What it does:
--   1. Validates the route exists
--   2. Blocks deletion of ACTIVE routes unless force := true
--   3. Unlinks any canary pair (clears canary_route_id on the sibling)
--   4. Decrements filter usage counts for all attached filters
--   5. Deletes route_filter rows (also handled by ON DELETE CASCADE)
--   6. Deletes the route_slo row  (also handled by ON DELETE CASCADE)
--   7. Deletes the route itself
--   8. Returns a summary of everything that was deleted
--
-- In dry_run mode nothing is mutated — the function returns what *would* happen.

CREATE OR REPLACE FUNCTION routify.delete_route(
    p_route_id UUID,
    force      BOOLEAN DEFAULT FALSE,
    dry_run    BOOLEAN DEFAULT FALSE
)
RETURNS TABLE (
    action      TEXT,
    entity      TEXT,
    entity_id   TEXT,
    detail      TEXT
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_route       RECORD;
    v_filter      RECORD;
    v_slo_exists  BOOLEAN;
    v_canary_id   UUID;
    v_primary_id  UUID;
BEGIN
    -- ── 1. Fetch the route ─────────────────────────────────────────────────
    SELECT r.id, r.tenant_id, r.name, r.status, r.environment,
           r.canary_route_id, r.is_canary, r.traffic_weight
      INTO v_route
      FROM routify.route r
     WHERE r.id = p_route_id;

    IF NOT FOUND THEN
        action   := 'ERROR';
        entity   := 'route';
        entity_id := p_route_id::TEXT;
        detail   := 'Route not found';
        RETURN NEXT;
        RETURN;
    END IF;

    -- ── 2. Guard: prevent deleting ACTIVE routes without force ─────────────
    IF v_route.status = 'ACTIVE' AND NOT force THEN
        action   := 'BLOCKED';
        entity   := 'route';
        entity_id := p_route_id::TEXT;
        detail   := format('Route ''%s'' is ACTIVE (status=%s, env=%s). '
                           'Use force := true to override or deactivate first.',
                           v_route.name, v_route.status, v_route.environment);
        RETURN NEXT;
        RETURN;
    END IF;

    -- ── 3. Report the route that will be deleted ───────────────────────────
    action   := CASE WHEN dry_run THEN 'WOULD_DELETE' ELSE 'DELETED' END;
    entity   := 'route';
    entity_id := p_route_id::TEXT;
    detail   := format('name=%s status=%s env=%s tenant=%s weight=%s',
                       v_route.name, v_route.status, v_route.environment,
                       v_route.tenant_id, v_route.traffic_weight);
    RETURN NEXT;

    -- ── 4. Unlink canary pair ──────────────────────────────────────────────
    -- If this route IS the primary (has canary_route_id set)
    IF v_route.canary_route_id IS NOT NULL THEN
        action   := CASE WHEN dry_run THEN 'WOULD_UNLINK' ELSE 'UNLINKED' END;
        entity   := 'canary_pair';
        entity_id := v_route.canary_route_id::TEXT;
        detail   := format('Cleared canary_route_id on primary (this route) pointing to canary %s',
                           v_route.canary_route_id);
        RETURN NEXT;

        IF NOT dry_run THEN
            -- The canary sibling's canaryPrimaryRouteId in extra_config
            -- becomes stale but that's OK — we're deleting the primary.
            -- Clear canary link on the sibling so it doesn't reference a ghost.
            UPDATE routify.route
               SET canary_route_id = NULL,
                   canary_auto_rollback_threshold = NULL,
                   traffic_weight = 100
             WHERE canary_route_id = p_route_id;
        END IF;
    END IF;

    -- If this route IS the canary (a sibling references us)
    SELECT r.id INTO v_primary_id
      FROM routify.route r
     WHERE r.canary_route_id = p_route_id;

    IF v_primary_id IS NOT NULL THEN
        action   := CASE WHEN dry_run THEN 'WOULD_UNLINK' ELSE 'UNLINKED' END;
        entity   := 'canary_pair';
        entity_id := v_primary_id::TEXT;
        detail   := format('Cleared canary_route_id on primary %s (was pointing to this canary)',
                           v_primary_id);
        RETURN NEXT;

        IF NOT dry_run THEN
            UPDATE routify.route
               SET canary_route_id = NULL,
                   canary_auto_rollback_threshold = NULL,
                   traffic_weight = 100
             WHERE id = v_primary_id;
        END IF;
    END IF;

    -- ── 5. Detach filters & decrement usage counts ─────────────────────────
    FOR v_filter IN
        SELECT rf.id AS rf_id, rf.filter_definition_id, fd.name AS filter_name
          FROM routify.route_filter rf
          JOIN routify.filter_definition fd ON fd.id = rf.filter_definition_id
         WHERE rf.route_id = p_route_id
    LOOP
        action   := CASE WHEN dry_run THEN 'WOULD_DETACH' ELSE 'DETACHED' END;
        entity   := 'route_filter';
        entity_id := v_filter.rf_id::TEXT;
        detail   := format('filter=%s (%s)', v_filter.filter_name, v_filter.filter_definition_id);
        RETURN NEXT;

        IF NOT dry_run THEN
            PERFORM routify.decrement_filter_usage(v_filter.filter_definition_id);
        END IF;
    END LOOP;

    -- ── 6. Report SLO deletion ─────────────────────────────────────────────
    SELECT EXISTS(SELECT 1 FROM routify.route_slo WHERE route_id = p_route_id)
      INTO v_slo_exists;

    IF v_slo_exists THEN
        action   := CASE WHEN dry_run THEN 'WOULD_DELETE' ELSE 'DELETED' END;
        entity   := 'route_slo';
        entity_id := p_route_id::TEXT;
        detail   := 'Cascade-deleted SLO configuration';
        RETURN NEXT;
    END IF;

    -- ── 7. Perform the actual delete (cascades to route_filter, route_slo) ─
    IF NOT dry_run THEN
        DELETE FROM routify.route WHERE id = p_route_id;
    END IF;

    -- ── 8. Summary ─────────────────────────────────────────────────────────
    action   := 'DONE';
    entity   := 'summary';
    entity_id := p_route_id::TEXT;
    detail   := CASE WHEN dry_run
                     THEN 'DRY RUN complete — no changes were made. Re-run without dry_run to execute.'
                     ELSE format('Route ''%s'' and all related data have been permanently deleted.',
                                 v_route.name)
                END;
    RETURN NEXT;
    RETURN;
END;
$$;

COMMENT ON FUNCTION routify.delete_route(UUID, BOOLEAN, BOOLEAN) IS
    'Admin utility: safely deletes a route and all related data (filters, SLO, canary links). '
    'Use dry_run := true to preview. Use force := true to delete ACTIVE routes.';

