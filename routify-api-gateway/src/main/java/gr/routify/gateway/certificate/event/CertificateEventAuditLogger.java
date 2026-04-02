package gr.routify.gateway.certificate.event;

import gr.routify.gateway.certificate.VersionedCertificate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs certificate lifecycle events to the audit log.
 */
@Slf4j
@Component
public class CertificateEventAuditLogger {

    @EventListener
    public void onCertificateRegistered(CertificateRegisteredEvent event) {
        var cert = event.getCertificate();
        log.info("[CERT-AUDIT] REGISTERED: id='{}', v{}, fp='{}', notAfter={}, source='{}'",
                cert.id(), cert.version(), cert.fingerprint(), cert.notAfter(), cert.source());
    }

    @EventListener
    public void onCertificateDeactivated(CertificateDeactivatedEvent event) {
        var cert = event.getCertificate();
        log.warn("[CERT-AUDIT] DEACTIVATED: id='{}', v{}, fp='{}', previousStatus={}",
                cert.id(), cert.version(), cert.fingerprint(), event.getPreviousStatus());
    }
}

