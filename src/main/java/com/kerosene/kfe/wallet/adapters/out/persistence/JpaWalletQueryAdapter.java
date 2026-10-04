package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.wallet.application.port.out.WalletQueryPort;
import com.kerosene.kfe.wallet.domain.model.AddressRole;
import com.kerosene.kfe.wallet.domain.model.AddressSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public final class JpaWalletQueryAdapter implements WalletQueryPort {
    private final KfeWalletRepository wallets;
    private final KfeWalletAddressRepository addresses;

    public JpaWalletQueryAdapter(
            KfeWalletRepository wallets,
            KfeWalletAddressRepository addresses) {
        this.wallets = wallets;
        this.addresses = addresses;
    }

    @Override
    public Optional<WalletSnapshot> findOwned(long ownerId, UUID walletId) {
        return wallets.findByIdAndUserId(walletId, ownerId).map(JpaWalletQueryAdapter::snapshot);
    }

    @Override
    public List<AddressSnapshot> activeAddresses(UUID walletId) {
        return addresses.findByWalletIdAndStatusOrderByCreatedAtDesc(
                        walletId,
                        com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus.ACTIVE)
                .stream()
                .map(JpaWalletQueryAdapter::snapshot)
                .toList();
    }

    private static WalletSnapshot snapshot(KfeWalletEntity wallet) {
        return new WalletSnapshot(
                wallet.getId(),
                wallet.getUserId(),
                WalletKind.valueOf(wallet.getKind().name()),
                WalletStatus.valueOf(wallet.getStatus().name()),
                wallet.getAsset(),
                wallet.isSpendable(),
                hasText(wallet.getXpub()),
                hasText(wallet.getDescriptor()),
                wallet.getXpub(),
                wallet.getDescriptor());
    }

    private static AddressSnapshot snapshot(KfeWalletAddressEntity address) {
        AddressRole role = address.getAddressRole() == null
                ? AddressRole.RECEIVE
                : AddressRole.valueOf(address.getAddressRole().name());
        return new AddressSnapshot(
                address.getId(),
                address.getWalletId(),
                address.getAddress(),
                role,
                address.getStatus() == com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus.ACTIVE,
                address.getDerivationPath(),
                address.getDerivationIndex());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
