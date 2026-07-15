package source.kfe.rail;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.ClassUtils;
import source.kfe.rail.KfeOnchainPaymentGateway;

public class ExternalRailProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExternalRailProviderRegistry.class);

    private final LightningInvoiceGateway lightningInvoiceGateway;
    private final LightningPaymentGateway lightningPaymentGateway;
    private final KfeOnchainPaymentGateway onchainCustodyPort;

    public ExternalRailProviderRegistry(
            LightningInvoiceGateway lightningInvoiceGateway,
            LightningPaymentGateway lightningPaymentGateway,
            KfeOnchainPaymentGateway onchainCustodyPort) {
        this.lightningInvoiceGateway = lightningInvoiceGateway;
        this.lightningPaymentGateway = lightningPaymentGateway;
        this.onchainCustodyPort = onchainCustodyPort;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logActiveProviders() {
        Map<String, RailProviderStatus> providers = activeProviders();
        log.info(
                "[ExternalRails] Active providers: lightningInvoice={} lightningPayment={} onchainOutbound={}",
                providers.get("lightningInvoice").summary(),
                providers.get("lightningPayment").summary(),
                providers.get("onchainOutbound").summary());
    }

    public Map<String, RailProviderStatus> activeProviders() {
        Map<String, RailProviderStatus> providers = new LinkedHashMap<>();
        providers.put("lightningInvoice", lightningStatus(lightningInvoiceGateway));
        providers.put("lightningPayment", lightningStatus(lightningPaymentGateway));
        providers.put("onchainOutbound", onchainStatus(onchainCustodyPort));
        return Map.copyOf(providers);
    }

    private RailProviderStatus lightningStatus(LightningInvoiceGateway gateway) {
        return new RailProviderStatus(
                safeProviderName(gateway),
                safeLive(gateway),
                ClassUtils.getUserClass(gateway).getSimpleName());
    }

    private RailProviderStatus lightningStatus(LightningPaymentGateway gateway) {
        return new RailProviderStatus(
                safeProviderName(gateway),
                safeLive(gateway),
                ClassUtils.getUserClass(gateway).getSimpleName());
    }

    private RailProviderStatus onchainStatus(KfeOnchainPaymentGateway port) {
        return new RailProviderStatus(
                safeProviderName(port),
                true,
                ClassUtils.getUserClass(port).getSimpleName());
    }

    private String safeProviderName(LightningInvoiceGateway gateway) {
        try {
            return gateway.providerName();
        } catch (RuntimeException exception) {
            return "UNAVAILABLE";
        }
    }

    private String safeProviderName(LightningPaymentGateway gateway) {
        try {
            return gateway.providerName();
        } catch (RuntimeException exception) {
            return "UNAVAILABLE";
        }
    }

    private String safeProviderName(KfeOnchainPaymentGateway port) {
        try {
            return port.providerName();
        } catch (RuntimeException exception) {
            return "UNAVAILABLE";
        }
    }

    private boolean safeLive(LightningInvoiceGateway gateway) {
        try {
            return gateway.isLive();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean safeLive(LightningPaymentGateway gateway) {
        try {
            return gateway.isLive();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public record RailProviderStatus(
            String providerName,
            boolean live,
            String implementation) {

        String summary() {
            return providerName + "/" + implementation + "/" + (live ? "live" : "not-live");
        }
    }
}
