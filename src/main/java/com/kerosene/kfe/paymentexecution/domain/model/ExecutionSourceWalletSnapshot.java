package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/** Committed source-wallet view at preparation time; not a revocation fence for a later RPC. */
public record ExecutionSourceWalletSnapshot(UUID id, long userId, String label, String asset,
                                           boolean active, boolean watchOnly, boolean spendable) {
    public boolean usableFor(long expectedUserId, UUID expectedId) {
        return expectedUserId > 0 && expectedId != null && expectedId.equals(id) && userId == expectedUserId
                && active && !watchOnly && spendable && "BTC".equals(asset) && label != null && !label.isBlank();
    }

    @Override public String toString() {
        return "ExecutionSourceWalletSnapshot[id=" + id + ", metadata=REDACTED]";
    }
}
