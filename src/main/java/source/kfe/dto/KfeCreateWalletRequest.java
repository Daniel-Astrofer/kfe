package source.kfe.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletName;

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
