package com.kerosene.kfe.rail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LightningPaymentOutcomeTest {

    @Test
    void normalizesProviderStatuses() {
        assertThat(LightningPaymentOutcome.fromProviderStatus("SUCCEEDED"))
                .isEqualTo(LightningPaymentOutcome.SUCCEEDED);
        assertThat(LightningPaymentOutcome.fromProviderStatus("failed"))
                .isEqualTo(LightningPaymentOutcome.FAILED);
        assertThat(LightningPaymentOutcome.fromProviderStatus("IN_FLIGHT"))
                .isEqualTo(LightningPaymentOutcome.IN_FLIGHT);
        assertThat(LightningPaymentOutcome.fromProviderStatus(null))
                .isEqualTo(LightningPaymentOutcome.UNKNOWN);
        assertThat(LightningPaymentOutcome.fromProviderStatus("weird"))
                .isEqualTo(LightningPaymentOutcome.UNKNOWN);
    }
}
