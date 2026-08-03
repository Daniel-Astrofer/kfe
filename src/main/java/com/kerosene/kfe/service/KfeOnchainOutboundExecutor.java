package com.kerosene.kfe.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.rail.KfeOnchainPaymentGateway;

import java.util.UUID;

@Service
public class KfeOnchainOutboundExecutor implements KfeRailExecution {

    private final KfeExecutionTransactionHelper transactionHelper;
    private final KfeOnchainPaymentGateway onchainPaymentGateway;
    private final KfePreparedExecutionService preparedExecutionService;

    public KfeOnchainOutboundExecutor(
            KfeExecutionTransactionHelper transactionHelper,
            @Qualifier("bitcoinCorePsbtKfeOnchainPaymentGateway")
            KfeOnchainPaymentGateway onchainPaymentGateway,
            KfePreparedExecutionService preparedExecutionService) {
        this.transactionHelper = transactionHelper;
        this.onchainPaymentGateway = onchainPaymentGateway;
        this.preparedExecutionService = preparedExecutionService;
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

        KfeOnchainPaymentGateway.PreparedOnchainPayment prepared = preparedExecutionService.load(
                        outboxId,
                        prep.transactionId(),
                        prep.claimToken(),
                        "ONCHAIN_OUTBOUND",
                        KfePreparedExecutionService.PayloadType.ONCHAIN,
                        KfeOnchainPaymentGateway.PreparedOnchainPayment.class)
                .map(KfePreparedExecutionService.StoredPayload::payload)
                .orElseGet(() -> {
                    KfeOnchainPaymentGateway.PreparedOnchainPayment created =
                            onchainPaymentGateway.prepareOnchain(
                                    new KfeOnchainPaymentGateway.OnchainPaymentCommand(
                                            prep.userId(),
                                            null,
                                            prep.sourceWalletLabel(),
                                            prep.externalReference(),
                                            prep.amountSats(),
                                            prep.networkFeeSats(),
                                            prep.memo() != null ? prep.memo() : "KFE on-chain outbound",
                                            prep.idempotencyKey(),
                                            prep.quorumProposalHash(),
                                            prep.feeRateSatsPerVbyte(),
                                            prep.feeTargetBlocks()));
                    try {
                        return preparedExecutionService.persistIfAbsent(
                                        outboxId,
                                        prep.transactionId(),
                                        prep.claimToken(),
                                        "ONCHAIN_OUTBOUND",
                                        KfePreparedExecutionService.PayloadType.ONCHAIN,
                                        created,
                                        created.expectedTxid(),
                                        KfeOnchainPaymentGateway.PreparedOnchainPayment.class)
                                .payload();
                    } catch (RuntimeException persistenceFailure) {
                        onchainPaymentGateway.releasePrepared(created);
                        throw persistenceFailure;
                    }
                });

        KfeOnchainPaymentGateway.PaymentResult result = onchainPaymentGateway.broadcastPrepared(prepared);
        if (result.txid() == null || !prepared.expectedTxid().equalsIgnoreCase(result.txid())) {
            throw new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                    "Broadcast result does not match the persisted prepared transaction.",
                    prepared.expectedTxid(),
                    result.rawPayload(),
                    null);
        }

        String providerReference = firstNonBlank(result.txid(), result.providerReference());
        // Broadcast only: reserve stays locked until the confirmation monitor settles.
        transactionHelper.recordOutboundBroadcast(
                outboxId,
                prep.transactionId(),
                prep.claimToken(),
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
