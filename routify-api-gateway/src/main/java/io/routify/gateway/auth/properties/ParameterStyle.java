package io.routify.gateway.auth.properties;

/**
 * How the token is passed in an OAuth2 verification request:
 * as a query param, form-body field, or custom header.
 */
public enum ParameterStyle {
    /** Token passed as a query parameter. */
    QUERY,
    /** Token passed in the request body (form-encoded). */
    BODY,
    /** Token passed as a custom HTTP header. */
    HEADER
}

