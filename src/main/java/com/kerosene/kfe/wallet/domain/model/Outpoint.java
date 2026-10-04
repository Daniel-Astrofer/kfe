package com.kerosene.kfe.wallet.domain.model;

public record Outpoint(String txid, int vout) {
    public Outpoint {
        if (txid == null || txid.isBlank()) {
            throw new IllegalArgumentException("UTXO txid is required.");
        }
        if (vout < 0) {
            throw new IllegalArgumentException("UTXO vout cannot be negative.");
        }
        txid = txid.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
