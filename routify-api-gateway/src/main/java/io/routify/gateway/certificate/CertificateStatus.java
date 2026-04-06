package io.routify.gateway.certificate;

/**
 * Lifecycle status of a registered certificate version.
 */
public enum CertificateStatus {
    /** The certificate is valid and being used for authentication. */
    ACTIVE,
    /** The certificate has passed its notAfter date. */
    EXPIRED,
    /** The certificate has been manually revoked/deactivated. */
    REVOKED
}

