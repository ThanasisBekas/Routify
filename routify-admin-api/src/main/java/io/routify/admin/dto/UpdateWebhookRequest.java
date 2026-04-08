package io.routify.admin.dto;

import java.util.List;

public record UpdateWebhookRequest(
        String name,
        String url,
        List<String> eventTypes
) {}

