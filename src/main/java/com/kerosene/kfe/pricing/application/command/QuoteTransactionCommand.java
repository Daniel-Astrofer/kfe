package com.kerosene.kfe.pricing.application.command;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;

/** Framework-independent input for calculating a transaction quote. */
public record QuoteTransactionCommand(
        PaymentRail rail,
        PaymentDirection direction,
        long amountSats,
        long requestedNetworkFeeSats) {

    public QuoteTransactionCommand {
        if (rail == null || direction == null) {
            throw new IllegalArgumentException("rail and direction are required");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive");
        }
        if (requestedNetworkFeeSats < 0L) {
            throw new IllegalArgumentException("requestedNetworkFeeSats must be non-negative");
        }
    }
}
