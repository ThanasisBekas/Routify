package io.routify.audit.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.routify.audit.domain.AlertEvent;
import io.routify.audit.domain.AlertRule;
import io.routify.audit.repository.AlertEventRepository;
import io.routify.audit.repository.AlertRuleRepository;
import io.routify.common.event.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates all enabled alert rules on a 60-second cycle.
 *
 * <p>State machine:
 * <pre>
 *                breached
 *   OK ─────────────────────► PENDING
 *   ▲                              │ breached for N consecutive checks
 *   │ resolved                     ▼
 *   └──────────────────────── FIRING
 *                               │ resolved
 *                               ▼
 *                              OK
 * </pre>
 *
 * <p>On PENDING → FIRING: persists an {@link AlertEvent} and publishes {@code ALERT_FIRED}
 * to {@code AUDIT_EVENTS} Kafka topic. On FIRING → OK: persists {@code ALERT_RESOLVED} event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEvaluationScheduler {

    private static final int DEFAULT_EVALUATION_INTERVAL_MINUTES = 1;

    private final AlertRuleRepository  alertRuleRepository;
    private final AlertEventRepository alertEventRepository;
    private final AlertMetricResolver  metricResolver;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper         objectMapper;

    @Scheduled(fixedDelayString = "${routify.alerts.evaluation-interval-ms:60000}")
    @Transactional
    public void evaluate() {
        List<AlertRule> enabledRules = alertRuleRepository.findByEnabledTrue();
        if (enabledRules.isEmpty()) return;

        log.debug("AlertEvaluationScheduler: evaluating {} rules", enabledRules.size());

        for (AlertRule rule : enabledRules) {
            try {
                double currentValue = metricResolver.resolve(rule);
                boolean breached = isBreached(rule, currentValue);
                transitionState(rule, breached, currentValue);
                rule.setLastEvaluatedAt(Instant.now());
                alertRuleRepository.save(rule);
            } catch (Exception e) {
                log.warn("AlertEvaluationScheduler: failed to evaluate rule {} ({}): {}",
                        rule.getId(), rule.getName(), e.getMessage());
            }
        }
    }

    private boolean isBreached(AlertRule rule, double currentValue) {
        double threshold = rule.getThreshold().doubleValue();
        return switch (rule.getOperator()) {
            case "GT"  -> currentValue > threshold;
            case "GTE" -> currentValue >= threshold;
            case "LT"  -> currentValue < threshold;
            case "LTE" -> currentValue <= threshold;
            case "EQ"  -> Double.compare(currentValue, threshold) == 0;
            default    -> false;
        };
    }

    private void transitionState(AlertRule rule, boolean breached, double currentValue) {
        String current = rule.getCurrentState();
        Instant now = Instant.now();

        switch (current) {
            case "OK" -> {
                if (breached) {
                    // Check cooldown: if last_fired_at + cooldown_minutes > now, suppress
                    if (isInCooldown(rule, now)) {
                        log.debug("Rule {} in cooldown, suppressing PENDING transition", rule.getId());
                        return;
                    }
                    rule.setCurrentState("PENDING");
                    rule.setConsecutiveBreaches(1);
                    rule.setStateChangedAt(now);
                    persistEvent(rule, "OK_TO_PENDING", currentValue);
                }
            }
            case "PENDING" -> {
                if (breached) {
                    int newBreaches = rule.getConsecutiveBreaches() + 1;
                    rule.setConsecutiveBreaches(newBreaches);

                    int requiredBreaches = requiredConsecutiveBreaches(rule);
                    if (newBreaches >= requiredBreaches) {
                        // Transition to FIRING
                        rule.setCurrentState("FIRING");
                        rule.setStateChangedAt(now);
                        rule.setLastFiredAt(now);
                        persistEvent(rule, "PENDING_TO_FIRING", currentValue);
                        publishAlertEvent("ALERT_FIRED", rule, currentValue);
                    }
                } else {
                    // Transient spike — auto-clear
                    rule.setCurrentState("OK");
                    rule.setConsecutiveBreaches(0);
                    rule.setStateChangedAt(now);
                }
            }
            case "FIRING" -> {
                if (!breached) {
                    // Resolved
                    rule.setCurrentState("OK");
                    rule.setConsecutiveBreaches(0);
                    rule.setStateChangedAt(now);
                    persistEvent(rule, "FIRING_TO_OK", currentValue);
                    publishAlertEvent("ALERT_RESOLVED", rule, currentValue);
                }
            }
            default -> log.warn("Unknown alert state '{}' for rule {}", current, rule.getId());
        }
    }

    private boolean isInCooldown(AlertRule rule, Instant now) {
        if (rule.getLastFiredAt() == null) return false;
        Instant cooldownEnd = rule.getLastFiredAt().plusSeconds((long) rule.getCooldownMinutes() * 60);
        return now.isBefore(cooldownEnd);
    }

    private int requiredConsecutiveBreaches(AlertRule rule) {
        return (int) Math.ceil((double) rule.getWindowMinutes() / DEFAULT_EVALUATION_INTERVAL_MINUTES);
    }

    private void persistEvent(AlertRule rule, String transition, double metricValue) {
        var event = new AlertEvent(
                rule.getId(),
                rule.getTenantId(),
                transition,
                BigDecimal.valueOf(metricValue),
                rule.getThreshold(),
                String.format("Rule '%s': %s (value=%.4f, threshold=%s)",
                        rule.getName(), transition, metricValue, rule.getThreshold()),
                Instant.now()
        );
        alertEventRepository.save(event);
    }

    private void publishAlertEvent(String eventType, AlertRule rule, double currentValue) {
        // Check if muted
        if (rule.getMutedUntil() != null && Instant.now().isBefore(rule.getMutedUntil())) {
            log.debug("Rule {} is muted until {}, suppressing {} notification",
                    rule.getId(), rule.getMutedUntil(), eventType);
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", eventType);
            payload.put("tenantId", rule.getTenantId().toString());
            payload.put("occurredAt", Instant.now().toString());
            payload.put("alert", Map.of(
                    "ruleId", rule.getId().toString(),
                    "ruleName", rule.getName(),
                    "metric", rule.getMetric(),
                    "routeId", rule.getRouteId() != null ? rule.getRouteId().toString() : "",
                    "threshold", rule.getThreshold(),
                    "currentValue", BigDecimal.valueOf(currentValue),
                    "severity", rule.getSeverity(),
                    "windowMinutes", rule.getWindowMinutes()
            ));

            kafkaTemplate.send(KafkaTopics.AUDIT_EVENTS,
                    rule.getTenantId().toString(),
                    objectMapper.writeValueAsString(payload));

            log.info("Published {} for rule '{}' (metric={}, value={}, threshold={})",
                    eventType, rule.getName(), rule.getMetric(), currentValue, rule.getThreshold());
        } catch (Exception e) {
            log.error("Failed to publish alert event to Kafka: {}", e.getMessage(), e);
        }
    }
}

