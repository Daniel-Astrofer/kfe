package source.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.rail.BlockchainClient;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeColdWalletObservationServiceTest {

    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BlockchainClient> blockchainClientProvider = mock(ObjectProvider.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncProvider = mock(ObjectProvider.class);
    private final BlockchainClient blockchainClient = mock(BlockchainClient.class);
    private final source.kfe.repository.KfeBalanceRepository balanceRepository =
            mock(source.kfe.repository.KfeBalanceRepository.class);
    private final KfeWalletDescriptorResolver descriptorResolver =
            mock(KfeWalletDescriptorResolver.class);
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate =
            mock(org.springframework.transaction.support.TransactionTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeMonitoredChainAddressIndex> addressIndexProvider =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<source.common.financial.FinancialNotificationPort>
            notificationPortProvider = mock(ObjectProvider.class);

    private KfeColdWalletObservationService service;

    @BeforeEach
    void setUp() {
        when(blockchainClientProvider.getIfAvailable()).thenReturn(blockchainClient);
        when(balanceSyncProvider.getIfAvailable()).thenReturn(null);
        when(addressIndexProvider.getIfAvailable()).thenReturn(null);
        when(notificationPortProvider.getIfAvailable()).thenReturn(null);
        when(balanceRepository.findByWalletIds(any())).thenReturn(List.of());
        when(blockchainClient.isOutpointUnspentIncludingMempool(any(), any(Integer.class)))
                .thenReturn(true);
        // Run callbacks inline so unit tests exercise doObserveWallet logic.
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<?> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(descriptorResolver.resolveReceiveDescriptor(any())).thenReturn(null);
        service = new KfeColdWalletObservationService(
                walletRepository,
                addressRepository,
                transactionRepository,
                blockchainClientProvider,
                statementService,
                dashboardPublisher,
                balanceSyncProvider,
                balanceRepository,
                descriptorResolver,
                addressIndexProvider,
                notificationPortProvider,
                transactionTemplate,
                20,
                3,
                50);
    }

    @Test
    void observeWalletCreatesInboundHistoryFromScannedUtxos() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = watchOnly(walletId, 9L);
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress("tb1qcoldreceive");

        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId)).thenReturn(List.of(address));
        when(blockchainClient.getUnspentOutputsMerged("tb1qcoldreceive")).thenReturn(List.of(
                new BlockchainClient.AddressUtxo(
                        "deadbeef", 0, 50_000L, "0014", 5, "tb1qcoldreceive")));
        when(blockchainClient.getUnspentOutputsFromScan(any(), any(Integer.class))).thenReturn(List.of());
        when(blockchainClient.getAddressTransactions(any())).thenReturn(null);
        when(transactionRepository.findByIdempotencyKey("cold-obs:" + walletId + ":deadbeef"))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByBlockchainTxidAndUserId("deadbeef", 9L)).thenReturn(List.of());
        when(transactionRepository.findByDestinationWalletIdAndProvider(
                        walletId, KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER))
                .thenReturn(List.of());
        when(transactionRepository.findByWalletIdAndStatusIn(eq(walletId), any())).thenReturn(List.of());
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.observeWallet(walletId);

        verify(transactionRepository).save(any(KfeTransactionEntity.class));
        verify(statementService).recordUserStatementIfAbsent(eq(9L), eq(walletId), any(), any());
        verify(dashboardPublisher).publishAfterCommit(9L);
    }

    @Test
    void observeWalletDoesNotFallbackToMempoolBlindSyncWhenDescriptorScanFails() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = watchOnly(walletId, 9L);
        wallet.setDescriptor("wpkh([aabbccdd/84h/1h/0h]tpubExample/0/*)");
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress("tb1qcoldreceive");

        KfeOnchainBalanceSyncService sync = mock(KfeOnchainBalanceSyncService.class);
        when(balanceSyncProvider.getIfAvailable()).thenReturn(sync);
        when(descriptorResolver.resolveReceiveDescriptor(wallet))
                .thenReturn("wpkh([aabbccdd/84h/1h/0h]tpubExample/0/*)");
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId)).thenReturn(List.of(address));
        // Descriptor scan fails (busy scantxoutset) — must not call syncWallet.
        when(blockchainClient.getUnspentOutputsFromScan(any(), any(Integer.class)))
                .thenThrow(new IllegalStateException("Scan already in progress"));
        when(blockchainClient.getUnspentOutputsMerged("tb1qcoldreceive")).thenReturn(List.of());
        when(transactionRepository.findByWalletIdAndStatusIn(eq(walletId), any())).thenReturn(List.of());
        when(transactionRepository.findByDestinationWalletIdAndProvider(
                        walletId, KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER))
                .thenReturn(List.of());
        when(balanceRepository.findByWalletIds(any())).thenReturn(List.of());

        service.observeWallet(walletId);

        org.mockito.Mockito.verify(sync, org.mockito.Mockito.never()).syncWallet(any());
        org.mockito.Mockito.verify(sync, org.mockito.Mockito.never())
                .applyObserved(eq(walletId), any(ChainProbeResult.class));
        org.mockito.Mockito.verify(sync, org.mockito.Mockito.never())
                .applyObserved(eq(walletId), any(Long.class));
    }

    @Test
    void recordColdPsbtBroadcastCreatesExecutingOutbound() {
        UUID walletId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(transactionRepository.findByIdempotencyKey("cold-psbt:" + workflowId)).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        KfeTransactionEntity tx = service.recordColdPsbtBroadcast(
                7L,
                walletId,
                workflowId,
                "abc123",
                10_000L,
                250L,
                "tb1qdest");

        assertThat(tx.getDirection()).isEqualTo(KfeDirection.OUTBOUND);
        assertThat(tx.getRail()).isEqualTo(KfeRail.ONCHAIN);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        assertThat(tx.getBlockchainTxid()).isEqualTo("abc123");
        assertThat(tx.getProvider()).isEqualTo(KfeColdWalletObservationService.PROVIDER_COLD_PSBT);
        assertThat(tx.getSourceWalletId()).isEqualTo(walletId);
        assertThat(tx.getTotalDebitSats()).isEqualTo(10_250L);
        verify(statementService).recordUserStatementIfAbsent(eq(7L), eq(walletId), any(), any());
    }

    @Test
    void touchColdConfirmationsSettlesWhenMinReached() {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(3L);
        tx.setProvider(KfeColdWalletObservationService.PROVIDER_COLD_PSBT);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setConfirmations(1);
        tx.setSourceWalletId(UUID.randomUUID());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        when(transactionRepository.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean settled = service.touchColdConfirmations(tx.getId(), 4);

        assertThat(settled).isTrue();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        assertThat(tx.getConfirmations()).isEqualTo(4);
    }

    private static KfeWalletEntity watchOnly(UUID id, long userId) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(id);
        wallet.setUserId(userId);
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        return wallet;
    }
}
