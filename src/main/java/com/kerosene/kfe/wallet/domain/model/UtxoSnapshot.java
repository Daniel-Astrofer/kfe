package com.kerosene.kfe.wallet.domain.model;

public record UtxoSnapshot(
        Outpoint outpoint,
        long valueSats,
        String scriptPubKey,
        String address,
        int confirmations) {

    public UtxoSnapshot {
        if (outpoint == null || valueSats <= 0) {
            throw new IllegalArgumentException("A UTXO must have a positive value and outpoint.");
        }
        confirmations = Math.max(0, confirmations);
    }
}
