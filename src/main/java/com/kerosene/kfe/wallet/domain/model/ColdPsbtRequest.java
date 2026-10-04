package com.kerosene.kfe.wallet.domain.model;

import java.util.List;

public record ColdPsbtRequest(
        String destinationAddress,
        long amountSats,
        Integer confirmationTarget,
        Long feeRateSatsPerVbyte,
        List<Outpoint> requestedInputs) {

    public ColdPsbtRequest {
        requestedInputs = requestedInputs == null ? List.of() : List.copyOf(requestedInputs);
    }
}
