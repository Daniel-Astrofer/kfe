package com.kerosene.kfe.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.rail.CustodyGateway;
import com.kerosene.kfe.rail.LightningPaymentGateway;
import com.kerosene.kfe.rail.LightningPaymentInFlightException;

import java.util.UUID;

@Service
public class KfeLightningOutboundExecutor implements KfeRailExecution {

    private final KfeExecutionTransactionHelper transactionHelper;
    private final LightningPaymentGateway lightningPaymentGateway;
    private final KfePreparedExecutionService preparedExecutionService;

    public KfeLightningOutboundExecutor(
            KfeExecutionTransactionHelper transactionHelper,
            @Qualifier("kfeExternalLightningPaymentGateway")
            LightningPaymentGateway lightningPaymentGateway,
            KfePreparedExecutionService preparedExecutionService) {
        this.transactionHelper = transactionHelper;
        this.lightningPaymentGateway = lightningPaymentGateway;
        this.preparedExecutionService = preparedExecutionService;
    }

    @Override
    public boolean supports(String operation) {
        return "LIGHTNING_OUTBOUND".equals(operation);
    }

    @Override
    public void execute(UUID outboxId, KfeExecutionTransactionHelper.PreparationResult prep) {
        if (prep.externalReference() == null || prep.externalReference().isBlank()) {
            throw new IllegalArgumentException(
                    "externalReference must contain a Lightning destination (invoice / LNURL / address / pubkey).");
        }
        if (!lightningPaymentGateway.isLive()) {
            throw new IllegalStateException(
                    "Lightning payment gateway is not live (" + lightningPaymentGateway.providerName() + ").");
        }

        LightningPaymentGateway.PreparedLightningPayment prepared = preparedExecutionService.load(
                        outboxId,
                        prep.transactionId(),
                        prep.claimToken(),
                        "LIGHTNING_OUTBOUND",
                        KfePreparedExecutionService.PayloadType.LIGHTNING,
                        LightningPaymentGateway.PreparedLightningPayment.class)
                .map(KfePreparedExecutionService.StoredPayload::payload)
                .orElseGet(() -> {
                    LightningPaymentGateway.PreparedLightningPayment created =
                            lightningPaymentGateway.prepareLightning(
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
                    return preparedExecutionService.persistIfAbsent(
                                    outboxId,
                                    prep.transactionId(),
                                    prep.claimToken(),
                                    "LIGHTNING_OUTBOUND",
                                    KfePreparedExecutionService.PayloadType.LIGHTNING,
                                    created,
                                    created.executionReference(),
                                    LightningPaymentGateway.PreparedLightningPayment.class)
                            .payload();
                });

        CustodyGateway.PaymentResult result = lightningPaymentGateway.payPreparedLightning(prepared);
        if (prepared.paymentHash() != null
                && result.paymentHash() != null
                && !prepared.paymentHash().equalsIgnoreCase(result.paymentHash())) {
            throw new LightningPaymentInFlightException(
                    "Lightning provider returned a different payment hash than the persisted operation.",
                    prepared.paymentHash(),
                    result.rawPayload());
        }

        // Only terminal SUCCEEDED reaches here (gateway throws on fail / in-flight).
        String paymentReference = firstNonBlank(result.paymentHash(), result.providerReference(), result.txid());
        transactionHelper.settleOutboundLightning(
                outboxId,
                prep.transactionId(),
                prep.claimToken(),
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
