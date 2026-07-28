package com.kerosene.kfe.rail;

import java.util.function.Consumer;

public interface LightningInvoiceGateway {

    boolean isLive();

    String providerName();

    CustodyGateway.GeneratedLightningInvoice createLightningInvoice(CustodyGateway.LightningInvoiceCommand command);

    CustodyGateway.IncomingLightningInvoiceStatus getLightningInvoiceStatus(CustodyGateway.LightningInvoiceStatusCommand command);

    boolean cancelLightningInvoice(CustodyGateway.LightningInvoiceCancellationCommand command);

    /**
     * Subscribe to real-time invoice updates from the Lightning node.
     * Implementations call {@code handler} for each invoice update event.
     * Returns a subscription handle that can be used to unsubscribe.
     * Default no-op for adapters that don't support streaming.
     *
     * @param handler callback for each invoice update
     * @return subscription handle (null if not supported)
     */
    default InvoiceSubscription subscribeInvoices(Consumer<CustodyGateway.IncomingLightningInvoiceStatus> handler) {
        return null;
    }

    /** Handle returned by {@link #subscribeInvoices}. */
    interface InvoiceSubscription {
        void unsubscribe();
    }
}
