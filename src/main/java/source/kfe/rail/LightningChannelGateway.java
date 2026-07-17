package source.kfe.rail;

import java.util.List;

/**
 * Structural Lightning channel operations (open / close / policy / inventory).
 * Separate from payment/invoice gateways (ISP).
 */
public interface LightningChannelGateway {

    boolean isLive();

    String providerName();

    List<ChannelSnapshot> listChannels();

    OpenChannelResult openChannel(OpenChannelCommand command);

    CloseChannelResult closeChannel(CloseChannelCommand command);

    UpdatePolicyResult updateChannelPolicy(UpdatePolicyCommand command);

    /**
     * Optional circular rebalance via self-payment. Default: unsupported.
     */
    default CircularRebalanceResult attemptCircularRebalance(CircularRebalanceCommand command) {
        return CircularRebalanceResult.unsupported(providerName());
    }

    record CircularRebalanceCommand(
            String targetChannelPoint,
            long amountSats,
            long maxFeeSats,
            String memo) {
    }

    record CircularRebalanceResult(
            boolean attempted,
            boolean succeeded,
            boolean supported,
            String paymentHash,
            long feeSats,
            String status,
            String rawPayload,
            String message) {

        public static CircularRebalanceResult unsupported(String provider) {
            return new CircularRebalanceResult(
                    false, false, false, null, 0L, "UNSUPPORTED", null,
                    "Circular rebalance not supported by " + provider);
        }

        public static CircularRebalanceResult failed(String status, String raw, String message) {
            return new CircularRebalanceResult(true, false, true, null, 0L, status, raw, message);
        }

        public static CircularRebalanceResult ok(String paymentHash, long feeSats, String raw) {
            return new CircularRebalanceResult(
                    true, true, true, paymentHash, feeSats, "SUCCEEDED", raw, "OK");
        }
    }

    record OpenChannelCommand(
            String peerPubkey,
            long localAmountSats,
            boolean privateChannel,
            boolean minConfsZero) {
    }

    record OpenChannelResult(
            String fundingTxid,
            String outputIndex,
            String channelPoint,
            String rawPayload) {
    }

    record CloseChannelCommand(
            String channelPoint,
            boolean force) {
    }

    record CloseChannelResult(
            String closingTxid,
            String rawPayload) {
    }

    record UpdatePolicyCommand(
            String channelPoint,
            long baseFeeMsat,
            long feeRatePpm,
            int timeLockDelta) {
    }

    record UpdatePolicyResult(boolean ok, String rawPayload) {
    }

    record ChannelSnapshot(
            String channelPoint,
            String remotePubkey,
            boolean active,
            long capacitySats,
            long localBalanceSats,
            long remoteBalanceSats,
            int pendingHtlcs,
            boolean initiator,
            long commitFeeSats,
            /** LND chan_id (uint64 as string), when known. */
            String chanId) {

        public ChannelSnapshot(
                String channelPoint,
                String remotePubkey,
                boolean active,
                long capacitySats,
                long localBalanceSats,
                long remoteBalanceSats,
                int pendingHtlcs,
                boolean initiator,
                long commitFeeSats) {
            this(
                    channelPoint,
                    remotePubkey,
                    active,
                    capacitySats,
                    localBalanceSats,
                    remoteBalanceSats,
                    pendingHtlcs,
                    initiator,
                    commitFeeSats,
                    null);
        }

        public double localRatio() {
            if (capacitySats <= 0L) {
                return 0.0d;
            }
            return (double) localBalanceSats / (double) capacitySats;
        }
    }
}
