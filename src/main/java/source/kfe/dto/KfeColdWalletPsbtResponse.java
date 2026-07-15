package source.kfe.dto;

import java.util.List;
import java.util.UUID;

public record KfeColdWalletPsbtResponse(
        UUID workflowId,
        String psbt,
        String psbtHash,
        long feeSats,
        long amountSats,
        String destinationAddress,
        List<KfeColdWalletPsbtRequest.Input> inputs) {
}
