package source.kfe.dto;

import java.util.List;
import java.util.UUID;

public record KfeReceivingCapabilitiesResponse(
        boolean canReceiveInternal,
        boolean canReceiveLightning,
        boolean canReceiveOnchain,
        String preferredRail,
        List<String> missingRequirements,
        String receiverDisplayName,
        UUID internalWalletId,
        /**
         * Active on-chain receive address for the receiver (if any).
         * Enables dual-rail send: INTERNAL ledger vs ONCHAIN to this address.
         */
        String onchainReceiveAddress,
        /** Wallet that owns {@link #onchainReceiveAddress}, when known. */
        UUID onchainWalletId,
        List<String> availableRails,
        Limits limits) {

    public record Limits(
            String asset,
            List<String> fiatCurrencies,
            long minInternalSats,
            long minLightningSats,
            long minOnchainSats) {
    }
}
