package io.routify.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AsyncAcknowledgement} — the standard HTTP 202 response body
 * for Kafka command endpoints.
 */
class AsyncAcknowledgementTest {

    @Test
    @DisplayName("of() factory creates acknowledgement with 'accepted' status")
    void ofFactoryCreatesWithAcceptedStatus() {
        var ack = AsyncAcknowledgement.of("Route creation dispatched");

        assertThat(ack.status()).isEqualTo("accepted");
        assertThat(ack.message()).isEqualTo("Route creation dispatched");
    }

    @Test
    @DisplayName("Direct constructor allows custom status")
    void directConstructor() {
        var ack = new AsyncAcknowledgement("queued", "Filter update queued");

        assertThat(ack.status()).isEqualTo("queued");
        assertThat(ack.message()).isEqualTo("Filter update queued");
    }

    @Test
    @DisplayName("Record equality works for same fields")
    void recordEquality() {
        var a = AsyncAcknowledgement.of("test");
        var b = AsyncAcknowledgement.of("test");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("Record inequality for different messages")
    void recordInequality() {
        var a = AsyncAcknowledgement.of("message-1");
        var b = AsyncAcknowledgement.of("message-2");

        assertThat(a).isNotEqualTo(b);
    }
}

