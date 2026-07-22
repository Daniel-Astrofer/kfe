package source.kfe.rail;

public interface LightningInvoiceGateway {

    boolean isLive();

    String providerName();

    CustodyGateway.GeneratedLightningInvoice createLightningInvoice(CustodyGateway.LightningInvoiceCommand command);

    CustodyGateway.IncomingLightningInvoiceStatus getLightningInvoiceStatus(CustodyGateway.LightningInvoiceStatusCommand command);

    boolean cancelLightningInvoice(CustodyGateway.LightningInvoiceCancellationCommand command);
}
