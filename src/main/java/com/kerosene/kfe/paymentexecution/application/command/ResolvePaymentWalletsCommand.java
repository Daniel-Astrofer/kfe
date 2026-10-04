package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import java.util.UUID;

/** The user comes from authentication, never from destination metadata. */
public record ResolvePaymentWalletsCommand(long userId, PaymentRail rail, PaymentDirection direction,
        UUID sourceWalletId, UUID destinationWalletId, String externalReference) {
    public ResolvePaymentWalletsCommand {
        if (userId <= 0L || rail == null || direction == null) {
            throw new IllegalArgumentException("authenticated user, rail and direction are required");
        }
    }

    public boolean requiresSourceReserve() {
        return direction == PaymentDirection.OUTBOUND || direction == PaymentDirection.INTERNAL;
    }

    @Override
    public String toString() {
        return "ResolvePaymentWalletsCommand[userId=" + userId + ", rail=" + rail
                + ", direction=" + direction + ", reference=REDACTED]";
    }
}
