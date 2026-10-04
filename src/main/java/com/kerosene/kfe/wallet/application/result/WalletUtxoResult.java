package com.kerosene.kfe.wallet.application.result;

import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;

import java.util.List;

public record WalletUtxoResult(List<UtxoSnapshot> utxos) {
    public WalletUtxoResult {
        utxos = utxos == null ? List.of() : List.copyOf(utxos);
    }
}
