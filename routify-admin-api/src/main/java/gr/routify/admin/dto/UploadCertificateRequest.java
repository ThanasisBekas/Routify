package gr.routify.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Typed request body for uploading a certificate to the vault.
 */
public record UploadCertificateRequest(
        UUID groupId,
        String memberAlias,
        @NotBlank String alias,
        String description,
        String format,
        @NotBlank String certPem,
        String privateKey
) {}

