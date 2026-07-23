package com.kerosene.kfe.rail;

/**
 * Mesh CHANNELS bucket → LND channel-funding inject.
 *
 * <p>Full inject (Intent debit CHANNELS + on-chain/LND wallet fund + open) is not
 * shipped. Adapters must be honest: never invent ledger or LND capital.
 */
public interface ChannelsMeshInjectGateway {

    /**
     * @return true only when a real mesh→LND inject path authorized {@code amountSats}
     *     from the CHANNELS bucket (not LND wallet balance alone).
     */
    InjectResult authorizeOpen(long amountSats, String peerPubkey);

    record InjectResult(boolean authorized, String reasonCode) {
        public static InjectResult refuse(String reasonCode) {
            return new InjectResult(false, reasonCode == null ? "CHANNELS_INJECT_REFUSED" : reasonCode);
        }

        public static InjectResult ok(String reasonCode) {
            return new InjectResult(true, reasonCode == null ? "CHANNELS_INJECT_OK" : reasonCode);
        }
    }
}
