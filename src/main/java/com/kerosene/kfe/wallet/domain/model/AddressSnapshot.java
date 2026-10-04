package com.kerosene.kfe.wallet.domain.model;

import java.util.UUID;

public record AddressSnapshot(
        UUID id,
        UUID walletId,
        String address,
        AddressRole role,
        boolean active,
        String derivationPath,
        Integer derivationIndex) {

    public AddressSnapshot {
        if (id == null || walletId == null) {
            throw new IllegalArgumentException("Address identity is required.");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Address is required.");
        }
        address = address.trim();
    }
}
