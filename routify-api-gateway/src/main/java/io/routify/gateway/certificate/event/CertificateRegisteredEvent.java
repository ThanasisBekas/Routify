package io.routify.gateway.certificate.event;

import io.routify.gateway.certificate.CertificateRegistry;
import io.routify.gateway.certificate.VersionedCertificate;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a new certificate version is registered in the {@link CertificateRegistry}.
 */
public class CertificateRegisteredEvent extends ApplicationEvent {

    private final VersionedCertificate certificate;

    public CertificateRegisteredEvent(Object source, VersionedCertificate certificate) {
        super(source);
        this.certificate = certificate;
    }

    public VersionedCertificate getCertificate() {
        return certificate;
    }
}

