package source.kfe.dto;

import source.kfe.model.KfeWalletAddressRole;
import source.kfe.model.KfeWalletAddressStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record KfeAddressResponse(
        UUID id,
        UUID walletId,
        String address,
        KfeWalletAddressRole role,
        KfeWalletAddressStatus status,
        String derivationPath,
        Integer derivationIndex,
        String providerReference,
        LocalDateTime createdAt,
        LocalDateTime retiredAt) {
}
