package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.AddressSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletQueryPort {
    Optional<WalletSnapshot> findOwned(long ownerId, UUID walletId);

    List<AddressSnapshot> activeAddresses(UUID walletId);
}
