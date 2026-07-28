package com.kerosene.kfe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.kerosene.common.audit.StructuredAuditLogger;
import com.kerosene.common.financial.FinancialMpcKeyPort;
import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.common.financial.FinancialQuorumPort;
import com.kerosene.common.financial.FinancialTickerPort;
import com.kerosene.common.financial.FinancialTransactionApprovalPort;
import com.kerosene.common.financial.FinancialUserDirectoryPort;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.service.KfeFinancialNotificationMetrics;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Configuration
public class KfeFinancialFallbackConfiguration {

    private static final String STANDALONE_MPC_UNAVAILABLE =
            "KFE standalone MPC key provisioning is unavailable.";
    private static final Logger log = LoggerFactory.getLogger(KfeFinancialFallbackConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(StructuredAuditLogger.class)
    public StructuredAuditLogger kfeStructuredAuditLogger() {
        return new StructuredAuditLogger();
    }

    @Bean
    @ConditionalOnMissingBean(AddressDerivationService.class)
    public AddressDerivationService kfeAddressDerivationService(
            @Value("${bitcoin.network:mainnet}") String network,
            @Value("${bitcoin.derivation.salt:kerosene_sovereign_salt_2026}") String salt) {
        return new AddressDerivationService(network, salt);
    }

    @Bean
    @ConditionalOnMissingBean(FinancialTickerPort.class)
    public FinancialTickerPort kfeFinancialTickerPort() {
        return currency -> {
            if ("usd".equalsIgnoreCase(currency)) {
                return new BigDecimal("65000");
            }
            if ("eur".equalsIgnoreCase(currency)) {
                return new BigDecimal("60000");
            }
            return new BigDecimal("325000");
        };
    }

    /**
     * Dev quorum simulation allowed only in test/local/dev profiles.
     * Production or any non-{test,local,dev} profile must provide a real FinancialQuorumPort
     * implementation (e.g. SovereignFinancialQuorumAdapter), otherwise boot fails.
     */
    @Bean
    @Profile({"test", "local", "dev"})
    @ConditionalOnMissingBean(FinancialQuorumPort.class)
    public FinancialQuorumPort kfeFinancialQuorumPortDev(
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int memberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int threshold) {
        log.warn("[KFE Config] DEV QUORUM SIMULATION: all proposals auto-approved "
                + "with {}/{} simulated unanimity. NEVER USE IN PRODUCTION.",
                threshold, memberCount);
        return proposalHash -> new FinancialQuorumPort.Result(threshold, memberCount);
    }

    /**
     * Production / non-dev startup guard: if no real FinancialQuorumPort is registered,
     * fail boot when production mode is enabled to prevent silent 1/1 fallback.
     */
    @Bean
    @Profile("!test & !local & !dev")
    @ConditionalOnMissingBean(FinancialQuorumPort.class)
    public FinancialQuorumPort kfeFinancialQuorumPortProductionGuard(
            @Value("${kfe.auth.production-mode:false}") boolean productionMode,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int memberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int threshold) {
        if (productionMode) {
            throw new IllegalStateException(
                    "[KFE Config] Production mode enabled (kfe.auth.production-mode=true) "
                    + "but no FinancialQuorumPort implementation found. "
                    + "Required: SovereignFinancialQuorumAdapter (kerosene-app) or equivalent "
                    + "quorum adapter. Refusing to run without real quorum in production.");
        }
        if (memberCount < threshold || threshold < 1 || memberCount < 1) {
            throw new IllegalStateException(
                    "[KFE Config] Invalid vault mesh constitution: "
                    + "memberCount=" + memberCount + ", threshold=" + threshold + ". "
                    + "Requires: memberCount >= threshold >= 1. "
                    + "Set kfe.vaultmesh.constitution.member-count and "
                    + "kfe.vaultmesh.constitution.threshold.");
        }
        log.error("[KFE Config] No FinancialQuorumPort implementation found in non-test profile. "
                + "All vault mesh quorum calls will fail. "
                + "Deploy a real quorum adapter (SovereignFinancialQuorumAdapter) before production.");
        return proposalHash -> {
            throw new IllegalStateException(
                    "[KFE Config] No FinancialQuorumPort implementation found. "
                    + "Mesh quorum proposals are fail-closed. "
                    + "Deploy SovereignFinancialQuorumAdapter for real custody quorum.");
        };
    }

    @Bean
    @ConditionalOnMissingBean(FinancialMpcKeyPort.class)
    public FinancialMpcKeyPort kfeFinancialMpcKeyPort(
            @Value("${kfe.standalone.mpc.dev-keygen-enabled:false}") boolean devKeygenEnabled,
            @Value("${REGION:${region:}}") String region) {
        if (devKeygenEnabled && "LOCAL".equalsIgnoreCase(region)) {
            return (walletId, userId) -> "kfe-local-dev-mpc-public-key:" + walletId + ":" + userId;
        }
        return (walletId, userId) -> {
            throw new IllegalStateException(STANDALONE_MPC_UNAVAILABLE);
        };
    }

    @Bean
    @ConditionalOnMissingBean(FinancialTransactionApprovalPort.class)
    public FinancialTransactionApprovalPort kfeFinancialTransactionApprovalPort() {
        return new FinancialTransactionApprovalPort() {
            @Override
            public void approveLocalFactor(Long userId, String deviceRef, String factor) {
                throw new IllegalStateException("KFE standalone transaction approval is unavailable.");
            }

            @Override
            public void approveCustodyTransfer(Long userId, String assertion) {
                throw new IllegalStateException("KFE standalone transaction approval is unavailable.");
            }

            @Override
            public void approveWalletOutbound(
                    Long actorUserId,
                    Long ownerUserId,
                    String factorA,
                    String factorB,
                    String factorC) {
                throw new IllegalStateException("KFE standalone transaction approval is unavailable.");
            }

            @Override
            public void approveColdWalletPsbt(Long userId, String factor) {
                throw new IllegalStateException("KFE standalone transaction approval is unavailable.");
            }
        };
    }

    /**
     * Noop notification port allowed only in test/local/dev profiles.
     * Production must have a real implementation (e.g. NotificationFinancialNotificationAdapter
     * or KfeRemoteFinancialNotificationClient), otherwise boot fails.
     */
    @Bean
    @Profile({"test", "local", "dev"})
    @ConditionalOnMissingBean(FinancialNotificationPort.class)
    public FinancialNotificationPort kfeFinancialNotificationPortNoop(
            KfeFinancialNotificationMetrics metrics) {
        metrics.recordPortActive("noop");
        log.warn("[KFE Config] FinancialNotificationPort fallback (noop) activated — non-production profile");
        return new FinancialNotificationPort() {
            @Override
            public void notifyDepositConfirmed(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long creditedSats, int confirmations) {
            }
            @Override
            public void notifyPaymentRequestDepositConfirmed(
                    Long userId, UUID transactionId, UUID paymentRequestId, String publicId,
                    UUID walletId, String rail, long creditedSats) {
            }
            @Override
            public void notifyDepositDetected(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long creditedSats, int confirmations) {
            }
            @Override
            public void notifyDepositConfirmationProgress(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long creditedSats, int confirmations) {
            }
            @Override
            public void notifyOutboundDetected(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long amountSats, int confirmations, String destinationHint) {
            }
            @Override
            public void notifyOutboundConfirmed(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long amountSats, int confirmations) {
            }
        };
    }

    /**
     * Production startup guard: if no real FinancialNotificationPort is registered, fail boot.
     * The health endpoint will report readiness DOWN until a real port bean is present.
     */
    @Bean
    @Profile("!test & !local & !dev")
    @ConditionalOnMissingBean(FinancialNotificationPort.class)
    public FinancialNotificationPort kfeFinancialNotificationPortProductionGuard(
            @Value("${kfe.auth.production-mode:false}") boolean productionMode) {
        if (productionMode) {
            throw new IllegalStateException(
                    "[KFE Config] Production mode enabled but no FinancialNotificationPort "
                    + "implementation found. Required: NotificationFinancialNotificationAdapter "
                    + "(kerosene-app) or KfeRemoteFinancialNotificationClient (kfe-service). "
                    + "Refusing to run with silent notification fallback in production.");
        }
        log.error("[KFE Config] No FinancialNotificationPort implementation found in non-test profile. "
                + "Notifications will be silently dropped. Deploy a real adapter before production.");
        return new FinancialNotificationPort() {
            @Override
            public void notifyDepositConfirmed(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long creditedSats, int confirmations) {
            }
            @Override
            public void notifyPaymentRequestDepositConfirmed(
                    Long userId, UUID transactionId, UUID paymentRequestId, String publicId,
                    UUID walletId, String rail, long creditedSats) {
            }
            @Override
            public void notifyDepositDetected(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long creditedSats, int confirmations) {
            }
            @Override
            public void notifyDepositConfirmationProgress(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long creditedSats, int confirmations) {
            }
            @Override
            public void notifyOutboundDetected(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long amountSats, int confirmations, String destinationHint) {
            }
            @Override
            public void notifyOutboundConfirmed(
                    Long userId, UUID transactionId, UUID walletId, String rail,
                    long amountSats, int confirmations) {
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(FinancialUserDirectoryPort.class)
    public FinancialUserDirectoryPort kfeFinancialUserDirectoryPort() {
        return new FinancialUserDirectoryPort() {
            @Override
            public Optional<FinancialUserHandle> findByUsername(String username) {
                return Optional.empty();
            }

            @Override
            public Optional<FinancialUserHandle> findById(Long userId) {
                return Optional.empty();
            }
        };
    }
}
