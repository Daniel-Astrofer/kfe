package source.kfe.dto;

public record KfeChannelSnapshotResponse(
        String channelPoint,
        String remotePubkey,
        boolean active,
        long capacitySats,
        long localBalanceSats,
        long remoteBalanceSats,
        int pendingHtlcs,
        boolean initiator,
        long commitFeeSats,
        double localRatio) {
}
