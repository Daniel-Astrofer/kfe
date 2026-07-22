package source.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;
import source.common.financial.FinancialNotificationPort;
import source.kfe.application.transaction.KfeBalanceMovementRecorder;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.rail.BlockchainClient;
import source.kfe.repository.KfeBalanceMovementRepository;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeCustodialDepositObservationServiceTest {

    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfeBalanceMovementRepository movementRepository = mock(KfeBalanceMovementRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BlockchainClient> blockchainClient = mock(ObjectProvider.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);
    private final KfePricingService pricingService = mock(KfePricingService.class);
    private final KfeFeeSettlementService feeSettlementService = mock(KfeFeeSettlementService.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeResponseMapper responseMapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FinancialNotificationPort> notificationPort = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSync =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeMonitoredChainAddressIndex> addressIndex =
            mock(ObjectProvider.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private KfeCustodialDepositObservationService service;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        service = new KfeCustodialDepositObservationService(
                walletRepository,
                addressRepository,
                transactionRepository,
                movementRepository,
                blockchainClient,
                balanceService,
                movementRecorder,
                pricingService,
                feeSettlementService,
                statementService,
                responseMapper,
                dashboardPublisher,
                auditLogService,
                notificationPort,
                onchainBalanceSync,
                addressIndex,
                transactionTemplate,
                30,
                3);
    }

    @Test
    void ingestZmqRawTxCreatesInboundWithoutListunspent() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setUserId(42L);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ACTIVE);

        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress("tb1qcustodial");
        address.setStatus(KfeWalletAddressStatus.ACTIVE);

        KfeMonitoredChainAddressIndex index = mock(KfeMonitoredChainAddressIndex.class);
        when(addressIndex.getIfAvailable()).thenReturn(index);
        when(index.walletIdForAddress("tb1qcustodial")).thenReturn(walletId);

        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByBlockchainTxidAndUserId("aabbccdd", 42L)).thenReturn(List.of());
        when(transactionRepository.findByIdempotencyKey("custodial-dep:" + walletId + ":aabbccdd"))
                .thenReturn(Optional.empty());
        when(pricingService.quote(any(), any(), eq(25_000L), eq(0L)))
                .thenReturn(new KfePricingService.Quote(25_000L, 25_000L, 0L, 0L));
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(responseMapper.buildDisplayPayload(any(), eq(42L))).thenReturn(java.util.Map.of());
        when(notificationPort.getIfAvailable()).thenReturn(null);

        var parsed = new KfeBitcoinZmqTxMatcher.ParsedRawTx(
                "aabbccdd",
                List.of(),
                List.of(new KfeBitcoinZmqTxMatcher.ParsedOutput("tb1qcustodial", 25_000L, 0)));

        service.ingestZmqRawTx(parsed, Set.of(walletId));

        verify(transactionRepository).save(argThat(tx ->
                tx.getDirection() == KfeDirection.INBOUND
                        && "aabbccdd".equals(tx.getBlockchainTxid())
                        && tx.getConfirmations() == 0
                        && walletId.equals(tx.getDestinationWalletId())));
        verify(statementService).recordUserStatement(eq(42L), eq(walletId), any(), any());
        verify(dashboardPublisher).publishAfterCommit(42L);
    }
}
