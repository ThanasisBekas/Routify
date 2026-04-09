package io.routify.cert.service;

import io.routify.cert.domain.AcmeAccount;
import io.routify.cert.domain.AcmeOrder;
import io.routify.cert.dto.CertificateDto;
import io.routify.cert.repository.AcmeAccountRepository;
import io.routify.cert.repository.AcmeOrderRepository;
import io.routify.common.exception.RoutifyException;
import io.routify.common.observability.RoutifyMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * ACME protocol service — manages account registration, certificate issuance,
 * and auto-renewal via Let's Encrypt / ZeroSSL.
 *
 * <p>Issued certificates are stored via the existing {@link CertificateVaultService}
 * upload path, which triggers the outbox → Kafka → gateway hot-reload pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcmeService {

    private static final String LETSENCRYPT_URL         = "acme://letsencrypt.org";
    private static final String LETSENCRYPT_STAGING_URL = "acme://letsencrypt.org/staging";
    private static final String ZEROSSSL_URL            = "acme://zerossl.com";

    private final AcmeAccountRepository  accountRepository;
    private final AcmeOrderRepository    orderRepository;
    private final CertEncryptionService  encryptionService;
    private final CertificateVaultService vaultService;
    private final AcmeChallengeStore     challengeStore;
    private final RoutifyMetrics         metrics;

    @Value("${routify.cert.acme.renewal-days-before:30}")
    private int renewalDaysBefore;

    // ─── Account Registration ──────────────────────────────────────────────────

    /**
     * Register an ACME account with the specified provider.
     *
     * @param tenantId the owning tenant
     * @param email    contact email for the ACME account
     * @param provider the ACME CA provider (LETSENCRYPT / ZEROSSSL)
     * @return the persisted account entity
     */
    @Transactional
    public AcmeAccount registerAccount(UUID tenantId, String email, AcmeAccount.AcmeProvider provider) {
        try {
            KeyPair accountKeyPair = generateKeyPair();

            String providerUrl = resolveProviderUrl(provider);
            Session session = new Session(providerUrl);
            Account acmeAccount = new AccountBuilder()
                    .addEmail(email)
                    .agreeToTermsOfService()
                    .useKeyPair(accountKeyPair)
                    .create(session);

            // Encrypt the key pair PEM
            String keyPairPem = keyPairToPem(accountKeyPair);
            CertEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt(keyPairPem);

            AcmeAccount entity = new AcmeAccount();
            entity.setTenantId(tenantId);
            entity.setEmail(email);
            entity.setAccountUrl(acmeAccount.getLocation().toString());
            entity.setKeyPairPem(encrypted.ciphertext());
            entity.setKeyPairIv(encrypted.iv());
            entity.setKeyPairTag(encrypted.tag());
            entity.setProvider(provider);
            entity.setStatus(AcmeAccount.AcmeAccountStatus.ACTIVE);

            entity = accountRepository.save(entity);
            log.info("ACME account registered: id={} email={} provider={} tenantId={}",
                    entity.getId(), email, provider, tenantId);
            return entity;

        } catch (AcmeException e) {
            metrics.recordAcmeFailure();
            throw new RoutifyException.BadRequest("ACME account registration failed: " + e.getMessage());
        }
    }

    // ─── Certificate Issuance ──────────────────────────────────────────────────

    /**
     * Request a certificate for a domain via the ACME HTTP-01 challenge flow.
     *
     * @param accountId   the ACME account to use
     * @param domain      the domain to issue a certificate for
     * @param certGroupId optional cert group to assign the issued certificate to
     * @param tenantId    the owning tenant
     * @return the persisted ACME order entity
     */
    @Transactional
    public AcmeOrder requestCertificate(UUID accountId, String domain, UUID certGroupId, UUID tenantId) {
        AcmeAccount account = accountRepository.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new RoutifyException.NotFound("AcmeAccount", accountId.toString()));

        try {
            KeyPair accountKeyPair = restoreKeyPair(account);
            String providerUrl = resolveProviderUrl(account.getProvider());
            Session session = new Session(providerUrl);
            Login login = session.login(java.net.URI.create(account.getAccountUrl()).toURL(), accountKeyPair);
            Account acmeAccount = login.getAccount();

            // Create ACME order
            Order order = acmeAccount.newOrder().domain(domain).create();

            // Find HTTP-01 challenge
            Authorization auth = order.getAuthorizations().getFirst();
            Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
                    .orElseThrow(() -> new RoutifyException.BadRequest(
                            "ACME server did not offer HTTP-01 challenge for domain: " + domain));

            // Store challenge for the HTTP-01 endpoint
            challengeStore.put(challenge.getToken(), challenge.getAuthorization());

            // Persist order
            AcmeOrder entity = new AcmeOrder();
            entity.setAccount(account);
            entity.setTenantId(tenantId);
            entity.setDomain(domain);
            entity.setCertGroupId(certGroupId);
            entity.setChallengeType(AcmeOrder.ChallengeType.HTTP_01);
            entity.setStatus(AcmeOrder.AcmeOrderStatus.VALIDATING);
            entity.setOrderUrl(order.getLocation().toString());
            entity.setChallengeToken(challenge.getToken());
            entity.setChallengeContent(challenge.getAuthorization());
            entity.setAutoRenew(true);
            entity = orderRepository.save(entity);

            // Trigger challenge validation
            challenge.trigger();

            // Poll for completion (in a virtual thread context, blocking is fine)
            pollOrderCompletion(order, entity, domain, certGroupId, tenantId);

            return entity;

        } catch (RoutifyException e) {
            metrics.recordAcmeFailure();
            throw e;
        } catch (Exception e) {
            metrics.recordAcmeFailure();
            throw new RoutifyException.BadRequest("ACME certificate request failed: " + e.getMessage());
        }
    }

    // ─── Certificate Renewal ───────────────────────────────────────────────────

    /**
     * Renew a certificate for an existing ACME order.
     * Creates a new order for the same domain, validates, and replaces the old cert.
     *
     * @param orderId the existing ACME order to renew
     */
    @Transactional
    public void renewCertificate(UUID orderId) {
        AcmeOrder existing = orderRepository.findById(orderId)
                .orElseThrow(() -> new RoutifyException.NotFound("AcmeOrder", orderId.toString()));

        try {
            // Revoke old certificate if it exists
            if (existing.getCertId() != null) {
                try {
                    vaultService.revokeCertificate(existing.getCertId(), existing.getTenantId(), "acme-renewal");
                } catch (Exception e) {
                    log.warn("Failed to revoke old cert during renewal: {}", e.getMessage());
                }
            }

            // Issue a new certificate using the same account and domain
            AcmeOrder newOrder = requestCertificate(
                    existing.getAccount().getId(),
                    existing.getDomain(),
                    existing.getCertGroupId(),
                    existing.getTenantId());

            // Update existing order with renewal info
            existing.setCertId(newOrder.getCertId());
            existing.setLastRenewedAt(Instant.now());
            existing.setNextRenewalAt(Instant.now().plus(90 - renewalDaysBefore, ChronoUnit.DAYS));
            existing.setStatus(AcmeOrder.AcmeOrderStatus.COMPLETED);
            existing.setErrorMessage(null);
            orderRepository.save(existing);

            metrics.recordAcmeRenewal();
            log.info("ACME renewal succeeded for domain={} orderId={}", existing.getDomain(), orderId);

        } catch (Exception e) {
            existing.setStatus(AcmeOrder.AcmeOrderStatus.RENEWAL_FAILED);
            existing.setErrorMessage(e.getMessage());
            orderRepository.save(existing);
            metrics.recordAcmeFailure();
            log.error("ACME renewal failed for domain={} orderId={}: {}",
                    existing.getDomain(), orderId, e.getMessage());
            throw new RoutifyException.BadRequest("ACME renewal failed: " + e.getMessage());
        }
    }

    // ─── Private Helpers ───────────────────────────────────────────────────────

    private void pollOrderCompletion(Order order, AcmeOrder entity,
                                      String domain,
                                      UUID certGroupId, UUID tenantId) throws Exception {
        // Poll for order completion (max 60 seconds, 2-second intervals)
        int maxAttempts = 30;
        for (int i = 0; i < maxAttempts; i++) {
            order.update();
            if (order.getStatus() == Status.VALID) {
                // Download certificate
                Certificate certificate = order.getCertificate();
                if (certificate == null) {
                    throw new RoutifyException.BadRequest("ACME order completed but no certificate returned");
                }

                // Convert cert chain to PEM
                String certPem = certificateToPem(certificate);

                // Store via existing upload pipeline
                String alias = "acme-" + domain + "-" + Instant.now().toEpochMilli();
                CertificateDto stored = vaultService.uploadCertificate(
                        tenantId, certGroupId, "acme-" + domain,
                        alias, "ACME-issued certificate for " + domain,
                        "PEM", certPem, null, "acme-service");

                // Update order
                entity.setCertId(stored.getId());
                entity.setStatus(AcmeOrder.AcmeOrderStatus.COMPLETED);
                entity.setLastRenewedAt(Instant.now());
                // Let's Encrypt certs are valid for 90 days; renew 30 days before
                entity.setNextRenewalAt(Instant.now().plus(90 - renewalDaysBefore, ChronoUnit.DAYS));
                orderRepository.save(entity);

                challengeStore.remove(entity.getChallengeToken());
                metrics.recordAcmeRenewal();
                return;
            }
            if (order.getStatus() == Status.INVALID) {
                entity.setStatus(AcmeOrder.AcmeOrderStatus.FAILED);
                entity.setErrorMessage("ACME order invalidated by CA");
                orderRepository.save(entity);
                challengeStore.remove(entity.getChallengeToken());
                metrics.recordAcmeFailure();
                throw new RoutifyException.BadRequest("ACME order was invalidated by the CA for domain: " + domain);
            }
            Thread.sleep(2000);
        }

        // Timeout
        entity.setStatus(AcmeOrder.AcmeOrderStatus.FAILED);
        entity.setErrorMessage("ACME order timed out after " + (maxAttempts * 2) + " seconds");
        orderRepository.save(entity);
        challengeStore.remove(entity.getChallengeToken());
        metrics.recordAcmeFailure();
        throw new RoutifyException.BadRequest("ACME order timed out for domain: " + domain);
    }

    private KeyPair restoreKeyPair(AcmeAccount account) {
        String decrypted = encryptionService.decrypt(
                new CertEncryptionService.EncryptedPayload(
                        account.getKeyPairPem(), account.getKeyPairIv(), account.getKeyPairTag()));
        return pemToKeyPair(decrypted);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RoutifyException.BadRequest("Failed to generate RSA key pair: " + e.getMessage());
        }
    }

    private static String resolveProviderUrl(AcmeAccount.AcmeProvider provider) {
        return switch (provider) {
            case LETSENCRYPT         -> LETSENCRYPT_URL;
            case LETSENCRYPT_STAGING -> LETSENCRYPT_STAGING_URL;
            case ZEROSSSL            -> ZEROSSSL_URL;
        };
    }

    private static String keyPairToPem(KeyPair keyPair) {
        try {
            StringWriter writer = new StringWriter();
            org.shredzone.acme4j.util.KeyPairUtils.writeKeyPair(keyPair, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RoutifyException.BadRequest("Failed to serialize key pair to PEM: " + e.getMessage());
        }
    }

    private static KeyPair pemToKeyPair(String pem) {
        try {
            return org.shredzone.acme4j.util.KeyPairUtils.readKeyPair(new java.io.StringReader(pem));
        } catch (Exception e) {
            throw new RoutifyException.BadRequest("Failed to restore key pair from PEM: " + e.getMessage());
        }
    }

    private static String certificateToPem(Certificate certificate) {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.security.cert.X509Certificate cert : certificate.getCertificateChain()) {
                java.util.Base64.Encoder encoder = java.util.Base64.getMimeEncoder(64, "\n".getBytes());
                sb.append("-----BEGIN CERTIFICATE-----\n");
                sb.append(encoder.encodeToString(cert.getEncoded()));
                sb.append("\n-----END CERTIFICATE-----\n");
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RoutifyException.BadRequest("Failed to convert certificate to PEM: " + e.getMessage());
        }
    }
}

