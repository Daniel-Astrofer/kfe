package com.kerosene.kfe.dto;

public record KfeUtxoResponse(
        String txid,
        int vout,
        long valueSats,
        String scriptPubKey,
        String address,
        int confirmations) {
    public KfeUtxoResponse(
            String txid,
            int vout,
            long valueSats,
            String scriptPubKey,
            String address) {
        this(txid, vout, valueSats, scriptPubKey, address, 0);
    }
}
