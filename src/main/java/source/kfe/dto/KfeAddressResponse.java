package source.kfe.dto;

import source.kfe.model.KfeWalletAddressRole;
import source.kfe.model.KfeWalletAddressStatus;

import java.time.Instant;
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
        Instant createdAt,
        Instant retiredAt) {
}
