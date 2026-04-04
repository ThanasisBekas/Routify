package gr.routify.gateway.certificate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled hourly — warns on upcoming expiry, marks expired certs in the registry,
 * and publishes Prometheus gauges.
 *
 * <p>All certificates are loaded exclusively from the Certificate Vault.
 * The expiry-warning threshold is fixed at 30 days. Previously this was
 * configurable via {@code certificate-store.expiry-warning}, but that
 * file-based configuration mechanism has been removed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertificateExpiryMonitor {

    /** Days before expiry to emit a warning log and set EXPIRING_SOON status. */
    private static final long EXPIRY_WARNING_DAYS = 30;

    private final CertificateRegistry registry;
    private final MeterRegistry meterRegistry;
    private final Set<String> registeredMetrics = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS, initialDelay = 1)
    public void checkExpiry() {
        for (var cert : registry.allCertificates()) {
            long days = cert.daysUntilExpiry();
            registerExpiryGauge(cert);
            if (days <= EXPIRY_WARNING_DAYS && days > 0) {
                log.warn("Certificate expiring soon: id='{}', v{}, fp='{}', daysLeft={}",
                        cert.id(), cert.version(), cert.fingerprint(), days);
            }
            if (days <= 0 && registry.getStatus(cert) == CertificateStatus.ACTIVE) {
                log.error("Certificate EXPIRED: id='{}', v{}, fp='{}'",
                        cert.id(), cert.version(), cert.fingerprint());
                registry.markExpired(cert.id(), cert.version());
            }
        }
        for (var id : registry.registeredIds()) {
            registerActiveVersionsGauge(id);
        }
    }

    private void registerExpiryGauge(VersionedCertificate cert) {
        var key = cert.id() + "-v" + cert.version();
        if (registeredMetrics.add(key)) {
            Gauge.builder("certificate.days_until_expiry", cert, VersionedCertificate::daysUntilExpiry)
                    .tags(Tags.of(
                            "id", cert.id(),
                            "version", String.valueOf(cert.version()),
                            "fingerprint", cert.fingerprint()))
                    .description("Days until certificate expires. Negative = already expired.")
                    .register(meterRegistry);
        }
    }

    private void registerActiveVersionsGauge(String logicalId) {
        var key = "active-" + logicalId;
        if (registeredMetrics.add(key)) {
            Gauge.builder("certificate.active_versions",
                            () -> registry.getActiveCertificates(logicalId).size())
                    .tag("id", logicalId)
                    .description("Number of active certificate versions")
                    .register(meterRegistry);
        }
    }
}
