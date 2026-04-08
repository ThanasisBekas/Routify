package io.routify.common.domain;

/**
 * Lightweight environment dimension for routes.
 *
 * <p>Routes tagged {@code STAGING} are only matched by the gateway when the request
 * carries an explicit {@code X-Route-Environment: STAGING} header. Routes tagged
 * {@code PRODUCTION} (the default) are matched normally.
 *
 * <p>Promotion copies staging config to the production version atomically.
 */
public enum RouteEnvironment {
    STAGING,
    PRODUCTION
}

