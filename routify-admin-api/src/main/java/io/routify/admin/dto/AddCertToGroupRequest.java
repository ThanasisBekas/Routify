package io.routify.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Typed request body for adding a certificate to a group.
 */
public record AddCertToGroupRequest(
        @NotNull UUID certId,
        String memberAlias
) {}

