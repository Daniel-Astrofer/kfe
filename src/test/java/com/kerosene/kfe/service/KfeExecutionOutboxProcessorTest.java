package com.kerosene.kfe.service;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.rail.CustodyGateway;
import com.kerosene.kfe.rail.KfeOnchainPaymentGateway;
import com.kerosene.kfe.rail.LightningPaymentGateway;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KfeExecutionOutboxProcessorTest {

    private final KfeExecutionTransactionHelper transactionHelper = mock(KfeExecutionTransactionHelper.class);
    private final KfeOnchainPaymentGateway onchainCustodyPort = mock(KfeOnchainPaymentGateway.class);
    private final LightningPaymentGateway lightningPaymentGateway = mock(LightningPaymentGateway.class);

    private final KfeExecutionOutboxProcessor processor = new KfeExecutionOutboxProcessor(
            transactionHelper,
            onchainCustodyPort,
            lightningPaymentGateway
    );

    @Test
    void processOnchainOutboundDelegatesToHelperAndGateway() {
        UUID outboxId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();

        KfeExecutionTransactionHelper.PreparationResult prep = new KfeExecutionTransactionHelper.PreparationResult(
                true,
                "ONCHAIN_OUTBOUND",
                txId,
                456L,
                "wallet-label",
                walletId,
                "1BitcoinAddress",
                50000L,
                500L,
                "memo-test",
                "idemp-key",
                "quorum-proposal",
                null,
                null,
                claimToken
        );

        when(transactionHelper.prepare(outboxId, claimToken)).thenReturn(prep);

        KfeOnchainPaymentGateway.PaymentResult paymentResult = new KfeOnchainPaymentGateway.PaymentResult(
                "ref-123",
                "txid-123",
                "hash-123",
                "SUCCESS",
                500L,
                "{}"
        );
        when(onchainCustodyPort.sendOnchain(any())).thenReturn(paymentResult);
        when(onchainCustodyPort.providerName()).thenReturn("btc-core");

        processor.process(new KfeExecutionOutboxService.ExecutionClaim(outboxId, claimToken));

        verify(transactionHelper).prepare(outboxId, claimToken);
        verify(onchainCustodyPort).sendOnchain(any());
        verify(transactionHelper).settleOutbound(
                eq(outboxId),
                eq(txId),
                eq(claimToken),
                eq("btc-core"),
                eq("txid-123"),
                eq("txid-123"),
                eq(500L),
                eq(walletId),
                eq("{}")
        );
    }

    @Test
    void processDoesNotCallProviderWhenPreparationDoesNotProceed() {
        UUID outboxId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        KfeExecutionTransactionHelper.PreparationResult terminal = new KfeExecutionTransactionHelper.PreparationResult(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                0L,
                null,
                null,
                null,
                null,
                null,
                claimToken
        );
        when(transactionHelper.prepare(outboxId, claimToken)).thenReturn(terminal);

        processor.process(new KfeExecutionOutboxService.ExecutionClaim(outboxId, claimToken));

        verify(transactionHelper).prepare(outboxId, claimToken);
        verifyNoInteractions(onchainCustodyPort, lightningPaymentGateway);
        verify(transactionHelper, never()).markFinalFailure(any(), any(), any(), any(), any());
        verify(transactionHelper, never()).markRetryableFailure(any(), any(), any(), any(), any());
    }
}
