package io.routify.gateway.certificate;

import io.routify.gateway.certificate.event.CertificateDeactivatedEvent;
import io.routify.gateway.certificate.event.CertificateRegisteredEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe, multi-version store for X.509 certificates keyed by logical ID.
 * Supports hot-reload, expiry tracking, and deactivation.
 */
@Slf4j
@Service
public class CertificateRegistry {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<VersionedCertificate>> store =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<VersionedCertificate, CertificateStatus> statusMap =
            new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;

    public CertificateRegistry(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Registers a new certificate version. Skips silently if an identical active cert already exists.
     */
    public VersionedCertificate register(String logicalId, X509Certificate certificate,
                                          PrivateKey privateKey, String source) {
        Objects.requireNonNull(logicalId, "Logical ID must not be null");
        Objects.requireNonNull(certificate, "Certificate must not be null");

        var versions = store.computeIfAbsent(logicalId, key -> new CopyOnWriteArrayList<>());

        var existing = versions.stream()
                .filter(v -> isActive(v) && v.certificate().equals(certificate))
                .findFirst();
        if (existing.isPresent()) {
            log.debug("Certificate already active: id='{}', v{}", logicalId, existing.get().version());
            return existing.get();
        }

        int nextVersion = versions.stream().mapToInt(VersionedCertificate::version).max().orElse(0) + 1;
        var entry = VersionedCertificate.of(logicalId, nextVersion, certificate, privateKey, source);
        versions.add(entry);
        statusMap.put(entry, CertificateStatus.ACTIVE);

        log.info("Registered certificate: id='{}', v{}, fp='{}', notAfter={}, source='{}'",
                logicalId, nextVersion, entry.fingerprint(), entry.notAfter(), source);
        eventPublisher.publishEvent(new CertificateRegisteredEvent(this, entry));
        return entry;
    }

    /** Registers a certificate without a private key. */
    public VersionedCertificate register(String logicalId, X509Certificate certificate, String source) {
        return register(logicalId, certificate, null, source);
    }

    /**
     * Finds an active version for the given logical ID that matches the incoming certificate.
     */
    public Optional<VersionedCertificate> matchCertificate(String logicalId, X509Certificate incoming) {
        if (logicalId == null || incoming == null) return Optional.empty();
        var versions = store.get(logicalId);
        if (versions == null) return Optional.empty();
        return versions.stream()
                .filter(this::isActive)
                .filter(v -> v.certificate().equals(incoming))
                .findFirst();
    }

    public boolean isActive(VersionedCertificate cert) {
        var status = statusMap.getOrDefault(cert, CertificateStatus.EXPIRED);
        return status == CertificateStatus.ACTIVE && !cert.isExpired();
    }

    public CertificateStatus getStatus(VersionedCertificate cert) {
        return statusMap.getOrDefault(cert, CertificateStatus.EXPIRED);
    }

    public List<VersionedCertificate> getActiveCertificates(String logicalId) {
        var versions = store.get(logicalId);
        if (versions == null) return List.of();
        return versions.stream().filter(this::isActive).toList();
    }

    public List<VersionedCertificate> getAllVersions(String logicalId) {
        var versions = store.get(logicalId);
        return versions != null ? List.copyOf(versions) : List.of();
    }

    public List<VersionedCertificate> allCertificates() {
        return store.values().stream().flatMap(Collection::stream).toList();
    }

    public Set<String> registeredIds() {
        return Collections.unmodifiableSet(store.keySet());
    }

    public Map<String, List<VersionedCertificate>> snapshot() {
        return store.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    public boolean deactivate(String logicalId, int version) {
        var versions = store.get(logicalId);
        if (versions == null) return false;
        return versions.stream()
                .filter(v -> v.version() == version)
                .findFirst()
                .map(cert -> {
                    var prev = statusMap.put(cert, CertificateStatus.REVOKED);
                    log.info("Deactivated: id='{}', v{}, fp='{}'", logicalId, version, cert.fingerprint());
                    eventPublisher.publishEvent(new CertificateDeactivatedEvent(this, cert,
                            prev != null ? prev : CertificateStatus.ACTIVE));
                    return true;
                }).orElse(false);
    }

    public void markExpired(String logicalId, int version) {
        var versions = store.get(logicalId);
        if (versions == null) return;
        versions.stream().filter(v -> v.version() == version).findFirst().ifPresent(cert -> {
            var prev = statusMap.put(cert, CertificateStatus.EXPIRED);
            log.warn("Certificate expired: id='{}', v{}", logicalId, version);
            eventPublisher.publishEvent(new CertificateDeactivatedEvent(this, cert,
                    prev != null ? prev : CertificateStatus.ACTIVE));
        });
    }

    public int purgeInactive(String logicalId) {
        var versions = store.get(logicalId);
        if (versions == null) return 0;
        var toRemove = versions.stream()
                .filter(v -> !isActive(v))
                .toList();
        toRemove.forEach(statusMap::remove);
        versions.removeAll(toRemove);
        if (!toRemove.isEmpty()) {
            log.info("Purged {} inactive versions for '{}'", toRemove.size(), logicalId);
        }
        return toRemove.size();
    }
}

