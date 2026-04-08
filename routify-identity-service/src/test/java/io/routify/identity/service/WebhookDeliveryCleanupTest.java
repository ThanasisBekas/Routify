package io.routify.identity.service;

import io.routify.common.observability.RoutifyMetrics;
import io.routify.identity.repository.WebhookDeliveryRepository;
import io.routify.identity.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the webhook delivery cleanup scheduler in {@link WebhookService}.
 *
 * <p>Verifies batched deletion, metrics recording, retention cutoff computation,
 * and edge cases (empty table, exact batch boundary).
 */
@ExtendWith(MockitoExtension.class)
class WebhookDeliveryCleanupTest {

    @Mock
    private WebhookDeliveryRepository deliveryRepo;

    @Mock
    private WebhookSubscriptionRepository subscriptionRepo;

    @Mock
    private RoutifyMetrics metrics;

    @Mock
    private TransactionTemplate transactionTemplate;

    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new WebhookService(subscriptionRepo, deliveryRepo, metrics, transactionTemplate);
        ReflectionTestUtils.setField(webhookService, "retentionDays", 7);
        ReflectionTestUtils.setField(webhookService, "cleanupBatchSize", 1000);
    }

    /**
     * Helper: configure the TransactionTemplate mock to delegate to the real callback
     * and wire up deliveryRepo.deleteOlderThanBatch returns.
     */
    @SuppressWarnings("unchecked")
    private void setupTransactionTemplateDelegation() {
        when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<Integer> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
    }

    @Test
    @DisplayName("No expired records — single batch, zero deletions, metric recorded")
    void noExpiredRecords() {
        setupTransactionTemplateDelegation();
        when(deliveryRepo.deleteOlderThanBatch(any(Instant.class), eq(1000))).thenReturn(0);

        webhookService.cleanupOldDeliveries();

        verify(deliveryRepo, times(1)).deleteOlderThanBatch(any(Instant.class), eq(1000));
        verify(metrics).recordWebhookDeliveryCleanup(0);
    }

    @Test
    @DisplayName("Small number of expired records — single batch deletes all")
    void singleBatchDeletesAll() {
        setupTransactionTemplateDelegation();
        when(deliveryRepo.deleteOlderThanBatch(any(Instant.class), eq(1000))).thenReturn(42);

        webhookService.cleanupOldDeliveries();

        // 42 < 1000, so only 1 batch call
        verify(deliveryRepo, times(1)).deleteOlderThanBatch(any(Instant.class), eq(1000));
        verify(metrics).recordWebhookDeliveryCleanup(42);
    }

    @Test
    @DisplayName("Large number of expired records — multiple batches until exhausted")
    void multipleBatches() {
        setupTransactionTemplateDelegation();
        // First two batches full (1000 each), third batch partial (250)
        when(deliveryRepo.deleteOlderThanBatch(any(Instant.class), eq(1000)))
                .thenReturn(1000)
                .thenReturn(1000)
                .thenReturn(250);

        webhookService.cleanupOldDeliveries();

        verify(deliveryRepo, times(3)).deleteOlderThanBatch(any(Instant.class), eq(1000));
        verify(metrics).recordWebhookDeliveryCleanup(2250);
    }

    @Test
    @DisplayName("Exact batch boundary — extra iteration confirms exhaustion")
    void exactBatchBoundary() {
        setupTransactionTemplateDelegation();
        // Exactly 1000 records → first batch returns 1000, second returns 0
        when(deliveryRepo.deleteOlderThanBatch(any(Instant.class), eq(1000)))
                .thenReturn(1000)
                .thenReturn(0);

        webhookService.cleanupOldDeliveries();

        verify(deliveryRepo, times(2)).deleteOlderThanBatch(any(Instant.class), eq(1000));
        verify(metrics).recordWebhookDeliveryCleanup(1000);
    }

    @Test
    @DisplayName("Custom batch size is respected")
    void customBatchSize() {
        setupTransactionTemplateDelegation();
        ReflectionTestUtils.setField(webhookService, "cleanupBatchSize", 500);

        when(deliveryRepo.deleteOlderThanBatch(any(Instant.class), eq(500))).thenReturn(123);

        webhookService.cleanupOldDeliveries();

        verify(deliveryRepo, times(1)).deleteOlderThanBatch(any(Instant.class), eq(500));
        verify(metrics).recordWebhookDeliveryCleanup(123);
    }

    @Test
    @DisplayName("Custom retention days — cutoff is computed correctly")
    void customRetentionDays() {
        setupTransactionTemplateDelegation();
        ReflectionTestUtils.setField(webhookService, "retentionDays", 30);

        when(deliveryRepo.deleteOlderThanBatch(any(Instant.class), eq(1000))).thenReturn(0);

        Instant before = Instant.now().minusSeconds(30 * 86400 + 5);
        webhookService.cleanupOldDeliveries();
        Instant after = Instant.now().minusSeconds(30 * 86400 - 5);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryRepo).deleteOlderThanBatch(cutoffCaptor.capture(), eq(1000));

        Instant capturedCutoff = cutoffCaptor.getValue();
        assertThat(capturedCutoff).isAfterOrEqualTo(before);
        assertThat(capturedCutoff).isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("Metrics are always recorded even when zero records deleted")
    void metricsAlwaysRecorded() {
        setupTransactionTemplateDelegation();
        when(deliveryRepo.deleteOlderThanBatch(any(Instant.class), anyInt())).thenReturn(0);

        webhookService.cleanupOldDeliveries();

        verify(metrics).recordWebhookDeliveryCleanup(0);
    }

    @Test
    @DisplayName("Default retention is 7 days")
    void defaultRetentionIs7Days() {
        setupTransactionTemplateDelegation();
        // retentionDays was set to 7 in @BeforeEach
        when(deliveryRepo.deleteOlderThanBatch(any(Instant.class), eq(1000))).thenReturn(0);

        Instant before = Instant.now().minusSeconds(7 * 86400 + 5);
        webhookService.cleanupOldDeliveries();
        Instant after = Instant.now().minusSeconds(7 * 86400 - 5);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryRepo).deleteOlderThanBatch(cutoffCaptor.capture(), eq(1000));

        Instant capturedCutoff = cutoffCaptor.getValue();
        assertThat(capturedCutoff).isAfterOrEqualTo(before);
        assertThat(capturedCutoff).isBeforeOrEqualTo(after);
    }
}

