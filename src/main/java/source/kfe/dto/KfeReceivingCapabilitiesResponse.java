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
