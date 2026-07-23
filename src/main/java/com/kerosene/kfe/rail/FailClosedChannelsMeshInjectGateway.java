package com.kerosene.kfe.rail;

/**
 * Honest stub: CHANNELS→LND inject is not wired.
 *
 * <p>Refuse channel opens that would treat empty/unrelated LND wallet funds as
 * mesh CHANNELS capital. See {@code VAULT_MESH_PLAN.md} Gaps and
 * {@code docs/backend/INFRASTRUCTURE.md}.
 */
public class FailClosedChannelsMeshInjectGateway implements ChannelsMeshInjectGateway {

    public static final String REASON = "CHANNELS_MESH_INJECT_NOT_WIRED";

    @Override
    public InjectResult authorizeOpen(long amountSats, String peerPubkey) {
        return InjectResult.refuse(REASON);
    }

    @Override
    public DebitResult reserveOpen(String intentId, long amountSats, String peerPubkey) {
        return DebitResult.refuse(REASON);
    }

    @Override
    public FundResult fundOpen(String intentId, long amountSats, String lndFundingAddress) {
        return FundResult.refuse(REASON);
    }

    @Override
    public InjectResult releaseOpen(String intentId, long amountSats, String peerPubkey) {
        return InjectResult.refuse(REASON);
    }

    @Override
    public InjectResult commitOpen(String intentId) {
        return InjectResult.refuse(REASON);
    }
}
