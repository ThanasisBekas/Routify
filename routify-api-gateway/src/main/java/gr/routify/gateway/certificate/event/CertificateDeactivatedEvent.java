package gr.routify.gateway.certificate.event;

import gr.routify.gateway.certificate.CertificateStatus;
import gr.routify.gateway.certificate.VersionedCertificate;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a certificate version is deactivated (revoked or expired).
 */
public class CertificateDeactivatedEvent extends ApplicationEvent {

    private final VersionedCertificate certificate;
    private final CertificateStatus previousStatus;

    public CertificateDeactivatedEvent(Object source, VersionedCertificate certificate,
                                        CertificateStatus previousStatus) {
        super(source);
        this.certificate = certificate;
        this.previousStatus = previousStatus;
    }

    public VersionedCertificate getCertificate() {
        return certificate;
    }

    public CertificateStatus getPreviousStatus() {
        return previousStatus;
    }
}

