package source.kfe.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import source.common.financial.FinancialWalletProvisioningPort;
import source.kfe.dto.KfeCreateWalletRequest;
import source.kfe.model.KfeWalletKind;
import source.kfe.service.KfeWalletService;

/**
 * Local monolith adapter for the Core -> KFE financial onboarding boundary.
 *
 * <p>When KFE is extracted to its own runtime, this class should be replaced by a remote client
 * implementing the same port from the Core side.</p>
 */
@Component
public class KfeFinancialWalletProvisioningAdapter implements FinancialWalletProvisioningPort {

    private static final Logger log = LoggerFactory.getLogger(KfeFinancialWalletProvisioningAdapter.class);

    private final KfeWalletService kfeWalletService;

    public KfeFinancialWalletProvisioningAdapter(KfeWalletService kfeWalletService) {
        this.kfeWalletService = kfeWalletService;
    }

    @Override
    public void ensurePrimaryWalletReady(Long userId, String initialAddress) {
        if (!kfeWalletService.listWallets(userId).isEmpty()) {
            return;
        }
        String normalizedInitialAddress = blankToNull(initialAddress);
        kfeWalletService.createWallet(
                userId,
                new KfeCreateWalletRequest(
                        KfeWalletKind.INTERNAL,
                        null,
                        "Conta Assegurada",
                        null,
                        null,
                        null,
                        null,
                        normalizedInitialAddress,
                        null,
                        null,
                        normalizedInitialAddress != null ? "SIGNUP_STATE_DEPOSIT_ADDRESS" : null,
                        false));
        log.info("[Onboarding] Primary KFE wallet created for userId={}", userId);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
