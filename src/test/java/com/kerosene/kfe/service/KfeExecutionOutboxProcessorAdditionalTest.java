package com.kerosene.kfe.service;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.rail.CustodyGateway;
import com.kerosene.kfe.rail.KfeOnchainPaymentGateway;
import com.kerosene.kfe.rail.LightningPaymentGateway;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeExecutionOutboxProcessorAdditionalTest {

    private final KfeExecutionTransactionHelper transactionHelper = mock(KfeExecutionTransactionHelper.class);
    private final KfeOnchainPaymentGateway onchainPaymentGateway = mock(KfeOnchainPaymentGateway.class);
    private final LightningPaymentGateway lightningPaymentGateway = mock(LightningPaymentGateway.class);
    private final KfeExecutionOutboxProcessor processor = new KfeExecutionOutboxProcessor(
            transactionHelper,
            onchainPaymentGateway,
            lightningPaymentGateway);

    private static KfeExecutionOutboxService.ExecutionClaim claim(UUID outboxId) {
        return new KfeExecutionOutboxService.ExecutionClaim(outboxId, UUID.randomUUID());
    }

    @Test
    void processDoesNothingWhenPreparationDeclinesExecution() {
        UUID outboxId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(
                        false,
                        "ONCHAIN_OUTBOUND",
                        UUID.randomUUID(),
                        10L,
                        "wallet",
                        UUID.randomUUID(),
                        "bc1qdestination",
                        1000L,
                        25L,
                        null,
                        "idempotency",
                        "proof",
                        null,
                        null,
                        executionClaim.claimToken()));

        processor.process(executionClaim);

        verify(onchainPaymentGateway, never()).sendOnchain(any());
        verify(lightningPaymentGateway, never()).payLightning(any());
    }

    @Test
    void processLightningOutboundSettlesWithPaymentHashAsProviderReference() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        KfeExecutionTransactionHelper.PreparationResult prep = new KfeExecutionTransactionHelper.PreparationResult(
                true,
                "LIGHTNING_OUTBOUND",
                transactionId,
                42L,
                "wallet-label",
                walletId,
                "lnbcrt1paymentrequest",
                2100L,
                15L,
                "memo",
                "idempotency",
                "quorum-proof",
                null,
                null,
                executionClaim.claimToken());
        CustodyGateway.PaymentResult result = new CustodyGateway.PaymentResult(
                "provider-ref",
                null,
                "payment-hash",
                "SUCCESS",
                2L,
                "{\"status\":\"ok\"}");

        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(prep);
        when(lightningPaymentGateway.payLightning(any())).thenReturn(result);
        when(lightningPaymentGateway.providerName()).thenReturn("lnd");

        processor.process(executionClaim);

        verify(lightningPaymentGateway).payLightning(any(CustodyGateway.LightningPaymentCommand.class));
        verify(transactionHelper).settleOutboundLightning(
                eq(outboxId),
                eq(transactionId),
                eq(executionClaim.claimToken()),
                eq("lnd"),
                eq("provider-ref"),
                eq(null),
                eq("payment-hash"),
                eq(2L),
                eq(walletId),
                eq("{\"status\":\"ok\"}"));
    }

    @Test
    void processUnsupportedOperationMarksFinalFailure() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(
                        true,
                        "DOGE_OUTBOUND",
                        transactionId,
                        42L,
                        "wallet-label",
                        UUID.randomUUID(),
                        "external",
                        2100L,
                        15L,
                        null,
                        "idempotency",
                        "quorum-proof",
                        null,
                        null,
                        executionClaim.claimToken()));

        processor.process(executionClaim);

        verify(transactionHelper).markFinalFailure(
                eq(outboxId),
                eq(transactionId),
                eq(executionClaim.claimToken()),
                eq("UNSUPPORTED_OPERATION"),
                eq("Unsupported KFE outbox operation DOGE_OUTBOUND."));
    }

    @Test
    void processMissingOnchainDestinationMarksFinalFailure() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(
                        true,
                        "ONCHAIN_OUTBOUND",
                        transactionId,
                        42L,
                        "wallet-label",
                        UUID.randomUUID(),
                        " ",
                        2100L,
                        15L,
                        null,
                        "idempotency",
                        "quorum-proof",
                        null,
                        null,
                        executionClaim.claimToken()));

        processor.process(executionClaim);

        verify(transactionHelper).markFinalFailure(
                eq(outboxId),
                eq(transactionId),
                eq(executionClaim.claimToken()),
                eq("PROVIDER_FINAL_FAILURE"),
                eq("externalReference must contain the destination address."));
    }

    @Test
    void processAmbiguousOnchainProviderOutcomeMarksUnknown() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        KfeExecutionTransactionHelper.PreparationResult prep = new KfeExecutionTransactionHelper.PreparationResult(
                true,
                "ONCHAIN_OUTBOUND",
                transactionId,
                42L,
                "wallet-label",
                UUID.randomUUID(),
                "bc1qdestination",
                2100L,
                15L,
                null,
                "idempotency",
                "quorum-proof",
                null,
                null,
                executionClaim.claimToken());
        KfeOnchainPaymentGateway.ProviderExecutionAmbiguous ambiguous =
                new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                        "broadcast result unknown",
                        "provider-ref",
                        "{\"ambiguous\":true}",
                        null);

        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(prep);
        when(onchainPaymentGateway.sendOnchain(any())).thenThrow(ambiguous);

        processor.process(executionClaim);

        verify(transactionHelper).markUnknown(
                eq(outboxId),
                eq(transactionId),
                eq(executionClaim.claimToken()),
                eq("provider-ref"),
                eq("{\"ambiguous\":true}"),
                eq("broadcast result unknown"));
    }
}
