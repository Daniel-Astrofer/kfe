package source.kfe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import source.common.audit.StructuredAuditLogger;
import source.common.financial.FinancialMpcKeyPort;
import source.common.financial.FinancialNotificationPort;
import source.common.financial.FinancialQuorumPort;
import source.common.financial.FinancialTickerPort;
import source.common.financial.FinancialTransactionApprovalPort;
import source.common.financial.FinancialUserDirectoryPort;
import source.common.service.AddressDerivationService;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Configuration
public class KfeFinancialFallbackConfiguration {

    private static final String STANDALONE_MPC_UNAVAILABLE =
            "KFE standalone MPC key provisioning is unavailable.";

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

    @Bean
    @ConditionalOnMissingBean(FinancialQuorumPort.class)
    public FinancialQuorumPort kfeFinancialQuorumPort() {
        return proposalHash -> new FinancialQuorumPort.Result(1, 1);
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

    @Bean
    @ConditionalOnMissingBean(FinancialNotificationPort.class)
    public FinancialNotificationPort kfeFinancialNotificationPort() {
        return new FinancialNotificationPort() {
            @Override
            public void notifyDepositConfirmed(
                    Long userId,
                    UUID transactionId,
                    UUID walletId,
                    String rail,
                    long creditedSats,
                    int confirmations) {
            }

            @Override
            public void notifyPaymentRequestDepositConfirmed(
                    Long userId,
                    UUID transactionId,
                    UUID paymentRequestId,
                    String publicId,
                    UUID walletId,
                    String rail,
                    long creditedSats) {
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
