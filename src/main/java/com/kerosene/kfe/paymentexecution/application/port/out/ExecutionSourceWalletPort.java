package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionSourceWalletSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionSourceWalletPort {
    /** Fresh, owner-scoped view in the caller's transaction; does not lock against subsequent archival. */
    Optional<ExecutionSourceWalletSnapshot> findOwned(long userId, UUID walletId);
}
