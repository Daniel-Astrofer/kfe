package source.kfe.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import source.kfe.rail.CustodyGateway;
import source.kfe.rail.KfeOnchainPaymentGateway;
import source.kfe.rail.LightningPaymentGateway;

import java.util.List;
import java.util.UUID;

@Service
public class KfeExecutionOutboxProcessor {

    private final KfeExecutionTransactionHelper transactionHelper;
    private final List<KfeRailExecution> railExecutors;

    @Autowired
    public KfeExecutionOutboxProcessor(
            KfeExecutionTransactionHelper transactionHelper,
            List<KfeRailExecution> railExecutors) {
        this.transactionHelper = transactionHelper;
        this.railExecutors = List.copyOf(railExecutors);
    }

    KfeExecutionOutboxProcessor(
            KfeExecutionTransactionHelper transactionHelper,
            KfeOnchainPaymentGateway onchainPaymentGateway,
            LightningPaymentGateway lightningPaymentGateway) {
        this.transactionHelper = transactionHelper;
        this.railExecutors = List.of(
                new InlineOnchainOutboundExecutor(transactionHelper, onchainPaymentGateway),
                new InlineLightningOutboundExecutor(transactionHelper, lightningPaymentGateway));
    }

    public void process(UUID outboxId) {
        KfeExecutionTransactionHelper.PreparationResult prep = transactionHelper.prepare(outboxId);
        if (prep == null || !prep.proceed()) {
            return;
        }

        try {
            KfeRailExecution executor = railExecutors.stream()
                    .filter(candidate -> candidate.supports(prep.operation()))
                    .findFirst()
                    .orElse(null);
            if (executor == null) {
                transactionHelper.markFinalFailure(
                        outboxId,
                        prep.transactionId(),
                        "UNSUPPORTED_OPERATION",
                        "Unsupported KFE outbox operation " + prep.operation() + ".");
                return;
            }
            executor.execute(outboxId, prep);
        } catch (KfeOnchainPaymentGateway.ProviderExecutionAmbiguous ambiguous) {
            transactionHelper.markUnknown(outboxId, prep.transactionId(), ambiguous.providerReference(), ambiguous.rawPayload(), ambiguous.getMessage());
        } catch (RuntimeException exception) {
            boolean retryable = isRetryable(exception);
            if (retryable) {
                transactionHelper.markRetryableFailure(outboxId, prep.transactionId(), "PROVIDER_RETRYABLE_FAILURE", safeMessage(exception));
            } else {
                transactionHelper.markFinalFailure(outboxId, prep.transactionId(), "PROVIDER_FINAL_FAILURE", safeMessage(exception));
            }
        }
    }

    private boolean isRetryable(RuntimeException exception) {
        return !(exception instanceof IllegalArgumentException)
                && !(exception instanceof UnsupportedOperationException);
    }

    private String safeMessage(Throwable exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank()
                ? exception.getMessage()
                : "KFE provider execution failed.";
    }

    private static final class InlineOnchainOutboundExecutor implements KfeRailExecution {
        private final KfeExecutionTransactionHelper transactionHelper;
        private final KfeOnchainPaymentGateway onchainPaymentGateway;

        private InlineOnchainOutboundExecutor(
                KfeExecutionTransactionHelper transactionHelper,
                KfeOnchainPaymentGateway onchainPaymentGateway) {
            this.transactionHelper = transactionHelper;
            this.onchainPaymentGateway = onchainPaymentGateway;
        }

        @Override
        public boolean supports(String operation) {
            return "ONCHAIN_OUTBOUND".equals(operation);
        }

        @Override
        public void execute(UUID outboxId, KfeExecutionTransactionHelper.PreparationResult prep) {
            if (prep.externalReference() == null || prep.externalReference().isBlank()) {
                throw new IllegalArgumentException("externalReference must contain the destination address.");
            }
            KfeOnchainPaymentGateway.PaymentResult result = onchainPaymentGateway.sendOnchain(
                    new KfeOnchainPaymentGateway.OnchainPaymentCommand(
                            prep.userId(),
                            null,
                            prep.sourceWalletLabel(),
                            prep.externalReference(),
                            prep.amountSats(),
                            prep.networkFeeSats(),
                            prep.memo() != null ? prep.memo() : "KFE on-chain outbound",
                            prep.idempotencyKey(),
                            prep.quorumProposalHash()));
            String providerReference = firstNonBlank(result.txid(), result.providerReference());
            transactionHelper.settleOutbound(
                    outboxId,
                    prep.transactionId(),
                    onchainPaymentGateway.providerName(),
                    providerReference,
                    result.txid(),
                    result.feeSats(),
                    prep.sourceWalletId(),
                    result.rawPayload());
        }
    }

    private static final class InlineLightningOutboundExecutor implements KfeRailExecution {
        private final KfeExecutionTransactionHelper transactionHelper;
        private final LightningPaymentGateway lightningPaymentGateway;

        private InlineLightningOutboundExecutor(
                KfeExecutionTransactionHelper transactionHelper,
                LightningPaymentGateway lightningPaymentGateway) {
            this.transactionHelper = transactionHelper;
            this.lightningPaymentGateway = lightningPaymentGateway;
        }

        @Override
        public boolean supports(String operation) {
            return "LIGHTNING_OUTBOUND".equals(operation);
        }

        @Override
        public void execute(UUID outboxId, KfeExecutionTransactionHelper.PreparationResult prep) {
            if (prep.externalReference() == null || prep.externalReference().isBlank()) {
                throw new IllegalArgumentException("externalReference must contain the Lightning payment request.");
            }
            CustodyGateway.PaymentResult result = lightningPaymentGateway.payLightning(
                    new CustodyGateway.LightningPaymentCommand(
                            prep.userId(),
                            null,
                            prep.sourceWalletLabel(),
                            prep.externalReference(),
                            prep.amountSats(),
                            prep.networkFeeSats(),
                            prep.memo() != null ? prep.memo() : "KFE lightning outbound",
                            prep.idempotencyKey(),
                            prep.quorumProposalHash()));
            String providerReference = firstNonBlank(result.paymentHash(), result.providerReference(), result.txid());
            transactionHelper.settleOutboundLightning(
                    outboxId,
                    prep.transactionId(),
                    lightningPaymentGateway.providerName(),
                    result.providerReference(),
                    result.txid(),
                    providerReference,
                    result.feeSats(),
                    prep.sourceWalletId(),
                    result.rawPayload());
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
