package source.kfe.integration;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import source.common.financial.FinancialRailProductionSafetyPort;
import source.kfe.rail.ConfigurableCustodyGateway;
import source.kfe.rail.KfeOnchainPaymentGateway;
import source.kfe.rail.LightningInvoiceGateway;
import source.kfe.rail.LightningPaymentGateway;

import java.util.ArrayList;
import java.util.List;

@Component
public class KfeFinancialRailProductionSafetyAdapter implements FinancialRailProductionSafetyPort {

    private static final String LIGHTNING_INVOICE_BEAN = "externalLightningInvoiceGateway";
    private static final String LIGHTNING_PAYMENT_BEAN = "externalLightningPaymentGateway";
    private static final String ONCHAIN_BEAN = "bitcoinCorePsbtKfeOnchainPaymentGateway";
    private static final String ONCHAIN_PROVIDER_NAME = "BITCOIN_CORE_QUORUM";

    private final ListableBeanFactory beanFactory;

    public KfeFinancialRailProductionSafetyAdapter(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public List<String> collectProductionViolations() {
        List<String> violations = new ArrayList<>();
        LightningInvoiceGateway invoiceGateway = requireBean(
                violations,
                LIGHTNING_INVOICE_BEAN,
                LightningInvoiceGateway.class,
                "Lightning invoice rail");
        if (invoiceGateway != null) {
            inspectLightningGateway(violations, "Lightning invoice rail", invoiceGateway);
        }

        LightningPaymentGateway paymentGateway = requireBean(
                violations,
                LIGHTNING_PAYMENT_BEAN,
                LightningPaymentGateway.class,
                "Lightning payment rail");
        if (paymentGateway != null) {
            inspectLightningGateway(violations, "Lightning payment rail", paymentGateway);
        }

        KfeOnchainPaymentGateway onchainPort = requireBean(
                violations,
                ONCHAIN_BEAN,
                KfeOnchainPaymentGateway.class,
                "On-chain outbound rail");
        if (onchainPort != null && !ONCHAIN_PROVIDER_NAME.equalsIgnoreCase(safeProviderName(onchainPort))) {
            violations.add("On-chain outbound rail must use " + ONCHAIN_PROVIDER_NAME + " in prod");
        }
        return List.copyOf(violations);
    }

    private <T> T requireBean(
            List<String> violations,
            String beanName,
            Class<T> beanType,
            String railName) {
        try {
            if (!beanFactory.containsBean(beanName)) {
                violations.add(railName + " provider bean " + beanName + " must be available in prod");
                return null;
            }
            return beanFactory.getBean(beanName, beanType);
        } catch (RuntimeException exception) {
            violations.add(railName + " provider bean " + beanName + " could not be verified");
            return null;
        }
    }

    private void inspectLightningGateway(
            List<String> violations,
            String railName,
            Object gateway) {
        if (isWeakConfigurableGateway(gateway)) {
            violations.add(railName + " must not use configurable custody gateway in prod");
        }
        if (!safeLive(gateway)) {
            violations.add(railName + " provider must be live in prod");
        }
    }

    private boolean isWeakConfigurableGateway(Object gateway) {
        Class<?> userClass = ClassUtils.getUserClass(gateway);
        return ConfigurableCustodyGateway.class.isAssignableFrom(userClass)
                || "ConfigurableCustodyGateway".equals(userClass.getSimpleName())
                || "BCX".equalsIgnoreCase(safeProviderName(gateway));
    }

    private boolean safeLive(Object gateway) {
        try {
            if (gateway instanceof LightningInvoiceGateway invoiceGateway) {
                return invoiceGateway.isLive();
            }
            if (gateway instanceof LightningPaymentGateway paymentGateway) {
                return paymentGateway.isLive();
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String safeProviderName(Object gateway) {
        try {
            if (gateway instanceof LightningInvoiceGateway invoiceGateway) {
                return invoiceGateway.providerName();
            }
            if (gateway instanceof LightningPaymentGateway paymentGateway) {
                return paymentGateway.providerName();
            }
            if (gateway instanceof KfeOnchainPaymentGateway custodyPort) {
                return custodyPort.providerName();
            }
            return "";
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
