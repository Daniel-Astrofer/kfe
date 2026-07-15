package source.kfe.dto;

public record KfeReserveOverviewResponse(
        double totalOnchainBtc,
        double lightningNodeBtc,
        double inboundLiquidityBtc,
        double outboundLiquidityBtc,
        double reservedOnchainBtc,
        double reservedLightningBtc,
        double availableOnchainBtc,
        double availableLightningBtc,
        boolean lightningSendsAllowed,
        String liquidityState) {
}
