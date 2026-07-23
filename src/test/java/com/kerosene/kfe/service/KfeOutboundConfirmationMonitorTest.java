package com.kerosene.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.repository.KfeTransactionRepository;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KfeOutboundConfirmationMonitorTest {

    @Mock
    private KfeTransactionRepository transactionRepository;
    @Mock
    private KfeExecutionTransactionHelper transactionHelper;
    @Mock
    private ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    @Mock
    private ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    @Mock
    private BitcoinCoreRpcClient core;

    private KfeOutboundConfirmationMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new KfeOutboundConfirmationMonitor(
                transactionRepository,
                transactionHelper,
                bitcoinCoreRpcClient,
                coldObservationService,
                50,
                1);
        when(bitcoinCoreRpcClient.getIfAvailable()).thenReturn(core);
        lenient().when(coldObservationService.getIfAvailable()).thenReturn(null);
    }

    @Test
    void persistsConfProgressBeforeSettleWhenMinConfirmationsReached() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.EXECUTING, 0, "aa".repeat(32));
        UUID txId = tx.getId();
        stubOutboundQueries(List.of(tx), List.of());
        when(core.findTransactionConfirmations(tx.getBlockchainTxid())).thenReturn(OptionalInt.of(2));
        when(transactionHelper.settleOutboundWhenConfirmed(txId, 2)).thenReturn(true);

        monitor.reconcileOutboundConfirmations();

        // Conf touch must run before settle so UI is not stuck at 0 if settle hangs.
        var inOrder = org.mockito.Mockito.inOrder(transactionHelper);
        inOrder.verify(transactionHelper).touchOutboundConfirmations(txId, 2);
        inOrder.verify(transactionHelper).settleOutboundWhenConfirmed(txId, 2);
    }

    @Test
    void stillTouchesConfsWhenBelowMinWithoutSettling() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.EXECUTING, 0, "bb".repeat(32));
        UUID txId = tx.getId();
        monitor = new KfeOutboundConfirmationMonitor(
                transactionRepository,
                transactionHelper,
                bitcoinCoreRpcClient,
                coldObservationService,
                50,
                3);
        when(bitcoinCoreRpcClient.getIfAvailable()).thenReturn(core);
        stubOutboundQueries(List.of(tx), List.of());
        when(core.findTransactionConfirmations(tx.getBlockchainTxid())).thenReturn(OptionalInt.of(1));

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).touchOutboundConfirmations(txId, 1);
        verify(transactionHelper, never()).settleOutboundWhenConfirmed(any(), anyInt());
    }

    @Test
    void advancesSettledOutboundRingsWithoutReSettling() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.SETTLED, 1, "cc".repeat(32));
        UUID txId = tx.getId();
        stubOutboundQueries(List.of(), List.of(tx));
        when(core.findTransactionConfirmations(tx.getBlockchainTxid())).thenReturn(OptionalInt.of(4));

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).touchOutboundConfirmations(txId, 4);
        verify(transactionHelper, never()).settleOutboundWhenConfirmed(any(), anyInt());
    }

    @Test
    void settleFailureDoesNotPreventConfTouch() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.EXECUTING, 0, "dd".repeat(32));
        UUID txId = tx.getId();
        stubOutboundQueries(List.of(tx), List.of());
        when(core.findTransactionConfirmations(tx.getBlockchainTxid())).thenReturn(OptionalInt.of(3));
        when(transactionHelper.settleOutboundWhenConfirmed(txId, 3))
                .thenThrow(new IllegalStateException("audit lock"));

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).touchOutboundConfirmations(txId, 3);
        verify(transactionHelper).settleOutboundWhenConfirmed(txId, 3);
    }

    @Test
    void includesSettledStatusInOutboundQuery() {
        stubOutboundQueries(List.of(), List.of());

        monitor.reconcileOutboundConfirmations();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KfeTransactionStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository, atLeastOnce()).findOutboundAwaitingConfirmation(
                eq(KfeRail.ONCHAIN),
                eq(KfeDirection.OUTBOUND),
                statuses.capture(),
                eq(6),
                any(Pageable.class));
        assertThat(statuses.getAllValues().stream().flatMap(List::stream).toList())
                .contains(KfeTransactionStatus.SETTLED);
    }

    private void stubOutboundQueries(
            List<KfeTransactionEntity> open, List<KfeTransactionEntity> settled) {
        when(transactionRepository.findOutboundAwaitingConfirmation(
                        eq(KfeRail.ONCHAIN),
                        eq(KfeDirection.OUTBOUND),
                        any(),
                        eq(6),
                        any(Pageable.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<KfeTransactionStatus> statuses =
                            (List<KfeTransactionStatus>) invocation.getArgument(2);
                    if (statuses != null && statuses.contains(KfeTransactionStatus.SETTLED)
                            && statuses.size() == 1) {
                        return settled;
                    }
                    return open;
                });
        when(transactionRepository.findOutboundAwaitingConfirmation(
                        eq(KfeRail.ONCHAIN),
                        eq(KfeDirection.INBOUND),
                        any(),
                        eq(6),
                        any(Pageable.class)))
                .thenReturn(List.of());
    }

    private static KfeTransactionEntity outbound(
            KfeTransactionStatus status, int confs, String txid) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(status);
        tx.setConfirmations(confs);
        tx.setBlockchainTxid(txid);
        tx.setSourceWalletId(UUID.randomUUID());
        tx.setUserId(1L);
        return tx;
    }
}
