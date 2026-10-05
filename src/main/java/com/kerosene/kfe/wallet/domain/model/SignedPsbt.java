package com.kerosene.kfe.wallet.domain.model;

import java.util.List;

public record SignedPsbt(List<Outpoint> inputs, List<Output> outputs) {
    public SignedPsbt {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    public record Output(String address, long valueSats) {
    }
}
