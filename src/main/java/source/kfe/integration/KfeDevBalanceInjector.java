package source.kfe.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import source.common.financial.DevBalanceInjector;
import source.common.financial.FinancialUserDirectoryPort;
import source.common.financial.FinancialNotificationPort;
import source.common.infra.logging.LogSanitizer;
import source.kfe.model.KfeWalletEntity;
import source.kfe.repository.KfeWalletRepository;
import source.kfe.service.KfeBalanceService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@Primary
public class KfeDevBalanceInjector implements DevBalanceInjector {

    private static final Logger log = LoggerFactory.getLogger(KfeDevBalanceInjector.class);

    private final KfeWalletRepository walletRepository;
    private final KfeBalanceService balanceService;
    private final FinancialUserDirectoryPort userDirectory;
    private final FinancialNotificationPort notificationPort;
    private final boolean enabled;

    public KfeDevBalanceInjector(
            KfeWalletRepository walletRepository,
            KfeBalanceService balanceService,
            FinancialUserDirectoryPort userDirectory,
            FinancialNotificationPort notificationPort,
            @Value("${app.dev.inject-test-balance:false}") boolean enabled) {
        this.walletRepository = walletRepository;
        this.balanceService = balanceService;
        this.userDirectory = userDirectory;
        this.notificationPort = notificationPort;
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public ClaimOutcome claimTestBalance(Long userId, UUID preferredWalletId) {
        return userDirectory.findById(userId)
                .map(user -> claimTestBalance(user, preferredWalletId))
                .orElse(ClaimOutcome.NO_WALLET);
    }

    private ClaimOutcome claimTestBalance(FinancialUserDirectoryPort.FinancialUserHandle user, UUID preferredWalletId) {
        if (!enabled) {
            return ClaimOutcome.DISABLED;
        }

        if (userDirectory.hasReceivedDemoCredit(user.id())) {
            return ClaimOutcome.ALREADY_CLAIMED;
        }

        try {
            KfeWalletEntity wallet = preferredWalletId != null
                    ? walletRepository.findByIdAndUserId(preferredWalletId, user.id()).orElse(null)
                    : walletRepository.findByUserIdOrderByCreatedAtDesc(user.id())
                            .stream()
                            .findFirst()
                            .orElse(null);
            if (wallet == null) {
                log.warn("[DEV] User {} has no wallets to inject balance.", user.username());
                return ClaimOutcome.NO_WALLET;
            }

            BigDecimal amount = new BigDecimal("100.00000000");
            long sats = amount.movePointRight(8).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
            balanceService.creditAvailable(wallet.getId(), "BTC", sats);

            userDirectory.markDemoCreditReceived(user.id());

            log.info("[DEV] Successfully injected 100 BTC one-time bonus for user {}", user.username());

            notificationPort.notifyDemoBalanceCredited(
                    user.id(),
                    wallet.getId(),
                    wallet.getLabel(),
                    amount.toPlainString());

            return ClaimOutcome.CLAIMED;
        } catch (Exception e) {
            log.error("[DEV] Failed to inject test balance for user {}", user.username(), e);
            return ClaimOutcome.ERROR;
        }
    }
}
