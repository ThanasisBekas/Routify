package io.routify.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateWebhookRequest(
        @NotBlank String name,
        @NotBlank String url,
        @NotEmpty List<String> eventTypes
) {}

