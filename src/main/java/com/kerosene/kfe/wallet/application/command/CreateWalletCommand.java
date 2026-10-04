package com.kerosene.kfe.wallet.application.command;

import com.kerosene.kfe.wallet.domain.model.WalletKind;

/** API-independent wallet creation intent. */
public record CreateWalletCommand(
        long ownerId,
        WalletKind kind,
        String name,
        String label,
        String xpub,
        String descriptor,
        String fingerprint,
        String derivationPath,
        String initialAddress,
        String initialAddressDerivationPath,
        Integer initialAddressDerivationIndex,
        String initialAddressProviderReference,
        Boolean issueInitialAddress) {
}
