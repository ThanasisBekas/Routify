package gr.routify.gateway.downstream.oauth2;

import java.util.Map;

/**
 * Immutable result of an OAuth2 token verification call —
 * HTTP status, raw body, and parsed claims map.
 */
public record TokenVerificationResponse(int statusCode, String body, Map<String, Object> claims) {}

