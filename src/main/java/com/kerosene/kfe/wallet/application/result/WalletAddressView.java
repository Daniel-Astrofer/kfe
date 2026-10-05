package com.kerosene.kfe.wallet.application.result;

import com.kerosene.kfe.wallet.domain.model.AddressRole;

import java.time.Instant;
import java.util.UUID;

public record WalletAddressView(
        UUID id,
        UUID walletId,
        String address,
        AddressRole role,
        String status,
        String derivationPath,
        Integer derivationIndex,
        String providerReference,
        Instant createdAt,
        Instant retiredAt) {
}
