package source.kfe.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import source.kfe.rail.KfeOnchainPaymentGateway;

import java.util.UUID;

@Service
public class KfeOnchainOutboundExecutor implements KfeRailExecution {

    private final KfeExecutionTransactionHelper transactionHelper;
    private final KfeOnchainPaymentGateway onchainPaymentGateway;

    public KfeOnchainOutboundExecutor(
            KfeExecutionTransactionHelper transactionHelper,
            @Qualifier("bitcoinCorePsbtKfeOnchainPaymentGateway")
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

    private String firstNonBlank(String... values) {
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
