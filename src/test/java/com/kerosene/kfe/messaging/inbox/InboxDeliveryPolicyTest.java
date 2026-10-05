package com.kerosene.kfe.messaging.inbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InboxDeliveryPolicyTest {
    private final InboxDeliveryPolicy policy = new InboxDeliveryPolicy();
    private final Instant now = Instant.parse("2026-09-26T12:00:00Z");

    @Test
    void redeliveryIsDeferredWhileAnotherClaimIsLive() {
        assertThat(policy.decide("PROCESSING", now.plusSeconds(30), now, 1, 5, true))
                .isEqualTo(InboxDeliveryDecision.DEFER);
        assertThat(policy.decide("PROCESSING", now.minusSeconds(1), now, 1, 5, true))
                .isEqualTo(InboxDeliveryDecision.PROCESS);
    }

    @Test
    void invalidOrExhaustedMessagesAreQuarantined() {
        assertThat(policy.decide("PENDING", null, now, 0, 5, false))
                .isEqualTo(InboxDeliveryDecision.QUARANTINE);
        assertThat(policy.decide("PENDING", null, now, 5, 5, true))
                .isEqualTo(InboxDeliveryDecision.QUARANTINE);
        assertThat(policy.decide("PROCESSED", null, now, 0, 5, true))
                .isEqualTo(InboxDeliveryDecision.DUPLICATE);
    }
}
