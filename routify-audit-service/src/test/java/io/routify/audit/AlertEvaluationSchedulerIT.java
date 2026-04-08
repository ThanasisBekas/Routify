package io.routify.audit;

import io.routify.audit.domain.AlertEvent;
import io.routify.audit.domain.AlertRule;
import io.routify.audit.scheduler.AlertEvaluationScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AlertEvaluationScheduler}.
 *
 * <p>Validates the alert rule state machine transitions by creating rules,
 * invoking the scheduler's evaluate method directly, and verifying
 * persisted alert events and state changes.
 *
 * <p>The scheduled evaluation is disabled via application-test.yml
 * ({@code routify.alerts.evaluation-interval-ms: 999999999}).
 * Tests call {@link AlertEvaluationScheduler#evaluate()} directly.
 */
class AlertEvaluationSchedulerIT extends AuditServiceIntegrationBase {

    @Autowired
    private AlertEvaluationScheduler scheduler;

    @Test
    @DisplayName("Alert rule transitions OK → PENDING when metric threshold is breached")
    void evaluate_breachedThreshold_transitionsOkToPending() {
        AlertRule rule = AlertRule.builder()
                .tenantId(TENANT_ID)
                .name("High DLQ Depth")
                .metric("DLQ_DEPTH")
                .operator("GT")
                .threshold(BigDecimal.ZERO)  // DLQ_DEPTH always > 0 since the base class cleans it
                .windowMinutes(5)
                .cooldownMinutes(0)
                .severity("WARNING")
                .enabled(true)
                .createdBy(ACTOR)
                .build();
        rule = alertRuleRepository.save(rule);

        // Seed a DLQ event to make DLQ_DEPTH > 0
        jdbcTemplate.execute(
                "INSERT INTO routify_audit.dlq_event (id, source_topic, dlq_topic, raw_payload, failed_at) " +
                "VALUES (gen_random_uuid(), 'test.topic', 'test.topic.DLQ', 'bad-data', NOW())");

        // Invoke evaluation
        scheduler.evaluate();

        // Verify state transition
        AlertRule updated = alertRuleRepository.findById(rule.getId()).orElseThrow();
        assertThat(updated.getCurrentState()).isEqualTo("PENDING");
        assertThat(updated.getConsecutiveBreaches()).isEqualTo(1);
        assertThat(updated.getLastEvaluatedAt()).isNotNull();

        // Verify an alert event was persisted
        var events = alertEventRepository.findAll();
        assertThat(events).isNotEmpty();
        AlertEvent event = events.getFirst();
        assertThat(event.getTransition()).isEqualTo("OK_TO_PENDING");
        assertThat(event.getRuleId()).isEqualTo(rule.getId());
        assertThat(event.getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("Alert rule transitions PENDING → OK when metric drops below threshold")
    void evaluate_belowThreshold_transitionsPendingToOk() {
        AlertRule rule = AlertRule.builder()
                .tenantId(TENANT_ID)
                .name("Error Rate Alert")
                .metric("DLQ_DEPTH")
                .operator("GT")
                .threshold(new BigDecimal("1000000"))  // Very high threshold — will never breach
                .windowMinutes(5)
                .cooldownMinutes(0)
                .severity("CRITICAL")
                .enabled(true)
                .createdBy(ACTOR)
                .build();
        // Simulate PENDING state
        rule.setCurrentState("PENDING");
        rule.setConsecutiveBreaches(1);
        rule.setStateChangedAt(Instant.now());
        rule = alertRuleRepository.save(rule);

        // Invoke evaluation — metric should be below threshold
        scheduler.evaluate();

        // Verify auto-clear: PENDING → OK
        AlertRule updated = alertRuleRepository.findById(rule.getId()).orElseThrow();
        assertThat(updated.getCurrentState()).isEqualTo("OK");
        assertThat(updated.getConsecutiveBreaches()).isEqualTo(0);
    }

    @Test
    @DisplayName("Disabled rules are not evaluated")
    void evaluate_disabledRule_isSkipped() {
        AlertRule rule = AlertRule.builder()
                .tenantId(TENANT_ID)
                .name("Disabled Rule")
                .metric("DLQ_DEPTH")
                .operator("GT")
                .threshold(BigDecimal.ZERO)
                .windowMinutes(5)
                .cooldownMinutes(0)
                .severity("WARNING")
                .enabled(false)  // Disabled
                .createdBy(ACTOR)
                .build();
        rule = alertRuleRepository.save(rule);

        scheduler.evaluate();

        AlertRule updated = alertRuleRepository.findById(rule.getId()).orElseThrow();
        assertThat(updated.getCurrentState()).isEqualTo("OK");
        assertThat(updated.getLastEvaluatedAt()).isNull();  // Never touched
    }

    @Test
    @DisplayName("Alert rule respects cooldown period after FIRING")
    void evaluate_withinCooldown_suppressesPending() {
        AlertRule rule = AlertRule.builder()
                .tenantId(TENANT_ID)
                .name("Cooldown Test")
                .metric("DLQ_DEPTH")
                .operator("GT")
                .threshold(BigDecimal.ZERO)
                .windowMinutes(5)
                .cooldownMinutes(60)  // 1 hour cooldown
                .severity("WARNING")
                .enabled(true)
                .createdBy(ACTOR)
                .build();
        // Simulate that this rule just fired
        rule.setLastFiredAt(Instant.now());
        rule = alertRuleRepository.save(rule);

        // Seed DLQ data so metric > 0
        jdbcTemplate.execute(
                "INSERT INTO routify_audit.dlq_event (id, source_topic, dlq_topic, raw_payload, failed_at) " +
                "VALUES (gen_random_uuid(), 'test.topic', 'test.topic.DLQ', 'bad-data', NOW())");

        scheduler.evaluate();

        // Should remain OK because we're in cooldown
        AlertRule updated = alertRuleRepository.findById(rule.getId()).orElseThrow();
        assertThat(updated.getCurrentState()).isEqualTo("OK");
    }
}

