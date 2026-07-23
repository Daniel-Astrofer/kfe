package com.kerosene.kfe.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletName;

public record KfeCreateWalletRequest(
        @NotNull KfeWalletKind kind,
        KfeWalletName name,
        @Size(max = 96) String label,
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
