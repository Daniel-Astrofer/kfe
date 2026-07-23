package com.kerosene.kfe.rail;

/**
 * Mesh CHANNELS bucket → LND channel-funding inject.
 *
 * <p>Decision-gate ({@link #authorizeOpen}) must not mutate the ledger. Execution uses
 * reserve → fund-bind (LND address) → LND open → commit (or release on open failure).
 */
public interface ChannelsMeshInjectGateway {

    /**
     * Decision-gate only: whether OPEN may proceed with mesh CHANNELS capital.
     * Must not debit / reserve / consume the ledger.
     */
    InjectResult authorizeOpen(long amountSats, String peerPubkey);

    /**
     * Soft-reserve CHANNELS capital for a specific open decision. Destination is the
     * mesh allowlist tag {@code ln-channel-rebalance} (not the LN peer pubkey).
     *
     * <p>Must be idempotent for the same {@code intentId} while the reservation is live
     * (crash after reserve / before open retries the same decision).
     *
     * @param intentId stable id (typically {@code channels-inject-open-<decisionId>})
     */
    default DebitResult reserveOpen(String intentId, long amountSats, String peerPubkey) {
        InjectResult gate = authorizeOpen(amountSats, peerPubkey);
        if (!gate.authorized()) {
            return DebitResult.refuse(gate.reasonCode());
        }
        return DebitResult.refuse("CHANNELS_INJECT_RESERVE_NOT_WIRED");
    }

    /**
     * Build + sign + broadcast CHANNELS Taproot PSBT to the bound LND funding address
     * (amount + fees). Fail-closed if Bitcoin Core / mesh sign / broadcast fails.
     * CHANNELS key ≠ USERS omnibus.
     */
    default FundResult fundOpen(String intentId, long amountSats, String lndFundingAddress) {
        return FundResult.refuse("CHANNELS_INJECT_FUND_UNSUPPORTED");
    }

    /**
     * Release a soft reservation after LND {@code openChannel} failure.
     */
    default InjectResult releaseOpen(String intentId, long amountSats, String peerPubkey) {
        return InjectResult.refuse("CHANNELS_INJECT_RELEASE_UNSUPPORTED");
    }

    /**
     * Durable-consume reservation after successful LND open.
     * Idempotent when the Intent was already committed (commit-retry / outbox).
     */
    default InjectResult commitOpen(String intentId) {
        return InjectResult.refuse("CHANNELS_INJECT_COMMIT_UNSUPPORTED");
    }

    record FundResult(boolean authorized, String fundingTxid, String reasonCode) {
        public static FundResult refuse(String reasonCode) {
            return new FundResult(
                    false, null, reasonCode == null ? "CHANNELS_INJECT_FUND_REFUSED" : reasonCode);
        }

        public static FundResult ok(String fundingTxid, String reasonCode) {
            return new FundResult(
                    true,
                    fundingTxid,
                    reasonCode == null ? "CHANNELS_INJECT_FUND_OK" : reasonCode);
        }
    }

    record InjectResult(boolean authorized, String reasonCode) {
        public static InjectResult refuse(String reasonCode) {
            return new InjectResult(false, reasonCode == null ? "CHANNELS_INJECT_REFUSED" : reasonCode);
        }

        public static InjectResult ok(String reasonCode) {
            return new InjectResult(true, reasonCode == null ? "CHANNELS_INJECT_OK" : reasonCode);
        }
    }

    record DebitResult(boolean authorized, String intentId, String reasonCode) {
        public static DebitResult refuse(String reasonCode) {
            return new DebitResult(
                    false, null, reasonCode == null ? "CHANNELS_INJECT_DEBIT_REFUSED" : reasonCode);
        }

        public static DebitResult ok(String intentId, String reasonCode) {
            return new DebitResult(
                    true,
                    intentId,
                    reasonCode == null ? "CHANNELS_INJECT_DEBIT_OK" : reasonCode);
        }
    }
}
