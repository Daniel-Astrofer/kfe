package source.kfe.service;

import org.junit.jupiter.api.Test;
import source.kfe.rail.CustodyGateway;
import source.kfe.rail.KfeOnchainPaymentGateway;
import source.kfe.rail.LightningPaymentGateway;

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
                "quorum-proposal"
        );

        when(transactionHelper.prepare(outboxId)).thenReturn(prep);

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

        processor.process(outboxId);

        verify(transactionHelper).prepare(outboxId);
        verify(onchainCustodyPort).sendOnchain(any());
        verify(transactionHelper).settleOutbound(
                eq(outboxId),
                eq(txId),
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
                null
        );
        when(transactionHelper.prepare(outboxId)).thenReturn(terminal);

        processor.process(outboxId);

        verify(transactionHelper).prepare(outboxId);
        verifyNoInteractions(onchainCustodyPort, lightningPaymentGateway);
        verify(transactionHelper, never()).markFinalFailure(any(), any(), any(), any());
        verify(transactionHelper, never()).markRetryableFailure(any(), any(), any(), any());
    }
}
