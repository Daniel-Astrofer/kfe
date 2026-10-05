package com.kerosene.kfe.wallet.application.result;

import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;
import java.util.UUID;

public record ColdPsbtResult(
        UUID workflowId,
        String psbt,
        String psbtHash,
        long feeSats,
        long amountSats,
        String destinationAddress,
        List<Outpoint> inputs) {
    public ColdPsbtResult {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
}
