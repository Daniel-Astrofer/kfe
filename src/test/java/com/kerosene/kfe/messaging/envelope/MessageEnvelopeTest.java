package com.kerosene.kfe.messaging.envelope;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEnvelopeTest {
    @Test
    void rejectsUnversionedOrEmptyMessages() {
        assertThatThrownBy(() -> new MessageEnvelope(
                UUID.randomUUID(), "EVENT", "payment.created", 0, Instant.now(),
                null, null, "payment-1", 1, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageEnvelope(
                UUID.randomUUID(), "EVENT", "payment.created", 1, Instant.now(),
                null, null, "payment-1", 1, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
