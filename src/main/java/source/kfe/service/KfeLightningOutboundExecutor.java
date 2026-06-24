package source.kfe.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import source.kfe.rail.CustodyGateway;
import source.kfe.rail.LightningPaymentGateway;

import java.util.UUID;

@Service
public class KfeLightningOutboundExecutor implements KfeRailExecution {

    private final KfeExecutionTransactionHelper transactionHelper;
    private final LightningPaymentGateway lightningPaymentGateway;

    public KfeLightningOutboundExecutor(
            KfeExecutionTransactionHelper transactionHelper,
            @Qualifier("kfeExternalLightningPaymentGateway")
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

        String paymentReference = firstNonBlank(result.paymentHash(), result.providerReference(), result.txid());
        transactionHelper.settleOutboundLightning(
                outboxId,
                prep.transactionId(),
                lightningPaymentGateway.providerName(),
                result.providerReference(),
                result.txid(),
                paymentReference,
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
