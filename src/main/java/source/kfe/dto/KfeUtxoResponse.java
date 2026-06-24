package source.kfe.dto;

public record KfeUtxoResponse(
        String txid,
        int vout,
        long valueSats,
        String scriptPubKey,
        String address) {
}
