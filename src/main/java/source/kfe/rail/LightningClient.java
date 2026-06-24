package source.kfe.rail;

/**
 * Interface for Lightning Network node operations (LND, Core Lightning, BTCPay).
 */
public interface LightningClient {

    /**
     * Total outbound capacity (local balance) across all active channels.
     */
    long getLocalBalance();

    /**
     * Total inbound capacity (remote balance) across all active channels.
     */
    long getRemoteBalance();

    /**
     * Current total balance of the LN node hot wallet (including channel funds).
     */
    long getLightningNodeBalance();

    /**
     * System uptime percentage (0.0 to 1.0).
     */
    double getNodeUptime();

    /**
     * Latency to the Liquidity Service Provider (LSP) node in milliseconds.
     */
    long getLspLatency();
}
