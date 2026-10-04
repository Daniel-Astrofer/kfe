package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentWalletLookupPort {
    /** Requires the financial transaction; scope in SQL and refresh after waiting for the lock. */
    Optional<PaymentWalletSnapshot> lockOwnedSource(long userId, UUID walletId);
    Optional<PaymentWalletSnapshot> findById(UUID walletId);
    Optional<PaymentWalletSnapshot> findOwnedDestination(long userId, UUID walletId);
    Optional<PaymentWalletSnapshot> findByAddress(String address);
    List<PaymentWalletSnapshot> findForUserNewestFirst(long userId);
}
