package gr.routify.gateway.certificate;

import gr.routify.gateway.client.CertVaultClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads all gateway-TLS-mapped certificates from routify-cert-vault into the in-memory
 * {@link CertificateRegistry} at application startup.
 *
 * <h3>Startup sequence</h3>
 * <ol>
 *   <li>On {@link ApplicationReadyEvent} (ordered <em>after</em> {@link CertificateFileWatcher}
 *       via {@code @Order(2)}), this component calls cert-vault via RabbitMQ to fetch the
 *       full list of ACTIVE certificates that have a {@code gatewayTlsLogicalId} mapping.</li>
 *   <li>For each such cert, it fetches the decrypted PEM material and registers it in the
 *       {@link CertificateRegistry} under its {@code gatewayTlsLogicalId}.</li>
 * </ol>
 *
 * <p>If cert-vault is unavailable at startup the gateway continues with only file-based
 * certificates loaded — a WARN is logged and the vault certs will be loaded on the next
 * {@code CERTIFICATE_MAPPED_TO_GATEWAY} Kafka event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CertificateVaultLoader {

    private final CertVaultClient      certVaultClient;
    private final CertificateRegistry  certificateRegistry;

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void loadVaultCertificates() {
        log.info("CertificateVaultLoader: loading gateway-mapped certificates from cert-vault...");
        try {
            List<Map<String, Object>> mapped = certVaultClient.fetchGatewayMappedCerts(null);
            if (mapped.isEmpty()) {
                log.info("CertificateVaultLoader: no gateway-mapped certificates found in cert-vault");
                return;
            }
            int loaded = 0;
            for (Map<String, Object> dto : mapped) {
                if (loadCertFromDto(dto)) loaded++;
            }
            log.info("CertificateVaultLoader: loaded {} gateway-mapped certificate(s) from cert-vault", loaded);
        } catch (Exception e) {
            log.warn("CertificateVaultLoader: failed to load vault certificates at startup — " +
                     "will retry on next Kafka event. Error: {}", e.getMessage(), e);
        }
    }

    /**
     * Loads a single cert described by a {@code CertificateDto}-shaped map into the registry.
     * Uses {@code effectiveGatewayLogicalId} as the registry key — which is the group's
     * logical ID for grouped certs, or the direct {@code gatewayTlsLogicalId} for standalone certs.
     *
     * @return {@code true} if the cert was successfully registered
     */
    public boolean loadCertFromDto(Map<String, Object> dto) {
        // Prefer effectiveGatewayLogicalId (group-aware); fall back to legacy field
        String effectiveLogicalId = str(dto.get("effectiveGatewayLogicalId"));
        if (effectiveLogicalId == null || effectiveLogicalId.isBlank()) {
            effectiveLogicalId = str(dto.get("gatewayTlsLogicalId"));
        }
        String certIdStr   = str(dto.get("id"));
        String tenantIdStr = str(dto.get("tenantId"));
        String alias       = str(dto.getOrDefault("alias", certIdStr));

        if (effectiveLogicalId == null || effectiveLogicalId.isBlank()) {
            log.debug("CertificateVaultLoader: skipping cert '{}' — no effective gateway logical ID", alias);
            return false;
        }

        UUID certId;
        UUID tenantId;
        try {
            certId   = UUID.fromString(certIdStr);
            tenantId = UUID.fromString(tenantIdStr);
        } catch (Exception e) {
            log.warn("CertificateVaultLoader: invalid UUID in cert dto (id='{}', tenantId='{}') — skipping",
                    certIdStr, tenantIdStr);
            return false;
        }

        Map<String, String> material = certVaultClient.fetchCertMaterial(certId, tenantId);
        if (material == null) {
            log.warn("CertificateVaultLoader: could not fetch material for certId={} (alias='{}') — skipping",
                    certId, alias);
            return false;
        }

        return registerMaterial(effectiveLogicalId, material, "vault:" + certId);
    }

    /**
     * Parses PEM material and registers it in the {@link CertificateRegistry}.
     *
     * @param logicalId gateway TLS logical ID (registry key)
     * @param material  map with {@code certPem} and optional {@code privateKeyPem}
     * @param source    human-readable source tag
     * @return {@code true} on success
     */
    public boolean registerMaterial(String logicalId, Map<String, String> material, String source) {
        String certPem       = material.get("certPem");
        String privateKeyPem = material.get("privateKeyPem");

        if (certPem == null || certPem.isBlank()) {
            log.warn("CertificateVaultLoader: empty certPem for logicalId='{}' — skipping", logicalId);
            return false;
        }

        try {
            List<X509Certificate> certs = PemCertificateParser.parseCertificates(certPem);
            PrivateKey privateKey = null;
            if (privateKeyPem != null && !privateKeyPem.isBlank()) {
                privateKey = PemCertificateParser.parsePrivateKey(privateKeyPem, null);
            }

            for (X509Certificate cert : certs) {
                certificateRegistry.register(logicalId, cert, privateKey, source);
            }
            log.info("CertificateVaultLoader: registered {} cert(s) under logicalId='{}' from source='{}'",
                    certs.size(), logicalId, source);
            return true;
        } catch (Exception e) {
            log.error("CertificateVaultLoader: failed to parse/register cert for logicalId='{}': {}",
                    logicalId, e.getMessage(), e);
            return false;
        }
    }

    private String str(Object val) {
        return val != null ? val.toString() : null;
    }
}

