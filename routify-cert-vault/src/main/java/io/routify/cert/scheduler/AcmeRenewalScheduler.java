package io.routify.cert.scheduler;

import io.routify.cert.domain.AcmeOrder;
import io.routify.cert.repository.AcmeOrderRepository;
import io.routify.cert.service.AcmeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduled task that checks for ACME certificates due for renewal and
 * triggers automatic renewal via the ACME protocol.
 *
 * <p>Runs daily at 03:00 UTC by default (configurable via
 * {@code routify.cert.acme.renewal-cron}). Certificates are considered due
 * for renewal when their {@code nextRenewalAt} is before now + renewal window.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcmeRenewalScheduler {

    private final AcmeService acmeService;
    private final AcmeOrderRepository orderRepository;

    @Value("${routify.cert.acme.renewal-days-before:30}")
    private int renewalDaysBefore;

    @Scheduled(cron = "${routify.cert.acme.renewal-cron:0 0 3 * * *}")
    public void checkAndRenew() {
        Instant renewalWindow = Instant.now().plus(renewalDaysBefore, ChronoUnit.DAYS);
        List<AcmeOrder> dueForRenewal = orderRepository
                .findByAutoRenewTrueAndNextRenewalAtBeforeAndStatusIn(
                        renewalWindow, List.of(AcmeOrder.AcmeOrderStatus.COMPLETED));

        if (dueForRenewal.isEmpty()) {
            log.debug("ACME renewal check: no certificates due for renewal");
            return;
        }

        log.info("ACME renewal check: {} certificate(s) due for renewal", dueForRenewal.size());

        for (AcmeOrder order : dueForRenewal) {
            try {
                acmeService.renewCertificate(order.getId());
                log.info("ACME renewal succeeded for domain={}", order.getDomain());
            } catch (Exception e) {
                log.error("ACME renewal failed for domain={}: {}", order.getDomain(), e.getMessage());
                // Error state is already persisted by AcmeService.renewCertificate()
            }
        }
    }
}

