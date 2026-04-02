package gr.routify.gateway.certificate.event;

import gr.routify.gateway.certificate.VersionedCertificate;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a new certificate version is registered in the {@link gr.routify.gateway.certificate.CertificateRegistry}.
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

