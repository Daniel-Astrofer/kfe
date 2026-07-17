package source.kfe.application.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import source.common.financial.FinancialTickerPort;
import source.kfe.application.settlement.BinarySettlementGate;
import source.kfe.application.settlement.SettlementGateCommand;
import source.kfe.application.settlement.SettlementGateResult;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.model.KfeIdempotencyEntity;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.repository.KfeTransactionRepository;
import source.common.financial.FinancialNotificationPort;
import source.kfe.service.KfeBalanceService;
import source.kfe.service.KfeDashboardPublisher;
import source.kfe.service.KfeExecutionOutboxProcessor;
import source.kfe.service.KfeExecutionOutboxService;
import source.kfe.service.KfeFeeSettlementService;
import source.kfe.service.KfeHashService;
import source.kfe.service.KfeLightningLiquidityService;
import source.kfe.service.KfePricingService;
import source.kfe.service.KfeResponseMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeSubmitTransactionUseCaseTest {

    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfePricingService pricingService = mock(KfePricingService.class);
    private final FinancialTickerPort tickerPort = mock(FinancialTickerPort.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final BinarySettlementGate binarySettlementGate = mock(BinarySettlementGate.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeResponseMapper responseMapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final KfeTransactionRequestValidator validator = mock(KfeTransactionRequestValidator.class);
    private final KfeTransactionAuthorizationUseCase authorizationUseCase = mock(KfeTransactionAuthorizationUseCase.class);
    private final KfeTransactionIdempotencyUseCase idempotencyUseCase = mock(KfeTransactionIdempotencyUseCase.class);
    private final KfeTransactionWalletResolver walletResolver = mock(KfeTransactionWalletResolver.class);
    private final KfeTransactionStateMachine stateMachine = mock(KfeTransactionStateMachine.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeTransactionOutboxUseCase outboxUseCase = mock(KfeTransactionOutboxUseCase.class);
    private final KfeTransactionStatementRecorder statementRecorder = mock(KfeTransactionStatementRecorder.class);
    private final KfeFeeSettlementService feeSettlementService = mock(KfeFeeSettlementService.class);
    private final KfeInternalPaymentRequestSettlementUseCase paymentRequestSettlementUseCase =
            mock(KfeInternalPaymentRequestSettlementUseCase.class);
    private final KfeLightningLiquidityService lightningLiquidityService = mock(KfeLightningLiquidityService.class);
    private final FinancialNotificationPort notificationPort = mock(FinancialNotificationPort.class);
    private final KfeExecutionOutboxService outboxService = mock(KfeExecutionOutboxService.class);
    private final KfeExecutionOutboxProcessor outboxProcessor = mock(KfeExecutionOutboxProcessor.class);
    /** No-op TX manager so TransactionTemplate runs the callback without a real DB. */
    private final org.springframework.transaction.PlatformTransactionManager transactionManager =
            new org.springframework.transaction.PlatformTransactionManager() {
                @Override
                public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition definition) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }

                @Override
                public void commit(org.springframework.transaction.TransactionStatus status) {
                }

                @Override
                public void rollback(org.springframework.transaction.TransactionStatus status) {
                }
            };

    private final KfeSubmitTransactionUseCase useCase = new KfeSubmitTransactionUseCase(
            transactionRepository,
            pricingService,
            tickerPort,
            balanceService,
            binarySettlementGate,
            hashService,
            responseMapper,
            dashboardPublisher,
            validator,
            authorizationUseCase,
            idempotencyUseCase,
            walletResolver,
            stateMachine,
            movementRecorder,
            outboxUseCase,
            statementRecorder,
            feeSettlementService,
            paymentRequestSettlementUseCase,
            lightningLiquidityService,
            notificationPort,
            outboxService,
            outboxProcessor,
            true,
            transactionManager
    );

    private void stubPassingGate() {
        when(binarySettlementGate.evaluateAndRequirePass(any(SettlementGateCommand.class)))
                .thenReturn(new SettlementGateResult(List.of(), 3, 3));
    }

    @Test
    void existingIdempotencyResponseSkipsAuthorizationAndPaymentRequestLock() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        String requestHash = "request-hash";
        when(walletResolver.resolveInternalDestinationReference(request)).thenReturn(request);
        KfeTransactionResponse existingResponse = transactionResponse();

        when(idempotencyUseCase.requestHash(userId, request)).thenReturn(requestHash);
        KfeIdempotencyEntity existingIdempotency = new KfeIdempotencyEntity();
        when(idempotencyUseCase.find(userId, request.idempotencyKey())).thenReturn(existingIdempotency);
        when(idempotencyUseCase.existingResponse(existingIdempotency, requestHash)).thenReturn(existingResponse);

        KfeTransactionResponse response = useCase.submit(userId, request);

        assertSame(existingResponse, response);
        verify(idempotencyUseCase).existingResponse(existingIdempotency, requestHash);
        verify(authorizationUseCase, never()).authorize(any(), any(), any());
        verify(walletResolver, never()).requireNotSelfPayment(any(), any());
        verify(paymentRequestSettlementUseCase, never()).lockAndValidate(any());
        verify(transactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentIdempotencyReservationReturnsCommittedTransactionResponse() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        String requestHash = "request-hash";
        KfeTransactionResponse existingResponse = transactionResponse();
        when(walletResolver.resolveInternalDestinationReference(request)).thenReturn(request);
        when(idempotencyUseCase.requestHash(userId, request)).thenReturn(requestHash);
        when(idempotencyUseCase.find(userId, request.idempotencyKey())).thenReturn(null);
        when(idempotencyUseCase.reserve(userId, request, requestHash))
                .thenThrow(new DataIntegrityViolationException("concurrent idempotency reservation"));
        when(idempotencyUseCase.getExistingByIdempotency(userId, request.idempotencyKey(), requestHash))
                .thenReturn(existingResponse);

        KfeTransactionResponse response = useCase.submit(userId, request);

        assertSame(existingResponse, response);
        verify(idempotencyUseCase).getExistingByIdempotency(userId, request.idempotencyKey(), requestHash);
        verify(paymentRequestSettlementUseCase, never()).lockAndValidate(any());
        verify(transactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedIdempotencyReservationDoesNotCreateTransactionIntent() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        String requestHash = "request-hash";
        when(walletResolver.resolveInternalDestinationReference(request)).thenReturn(request);

        when(idempotencyUseCase.requestHash(userId, request)).thenReturn(requestHash);
        when(idempotencyUseCase.find(userId, request.idempotencyKey())).thenReturn(null);
        when(idempotencyUseCase.reserve(userId, request, requestHash))
                .thenThrow(new IllegalStateException("duplicate idempotency reservation"));

        assertThrows(IllegalStateException.class, () -> useCase.submit(userId, request));

        verify(idempotencyUseCase).reserve(userId, request, requestHash);
        verify(transactionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistsExternalReferenceAndMemoInTransactionMasterIntent() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        KfeIdempotencyEntity idempotency = new KfeIdempotencyEntity();
        KfeWalletEntity sourceWallet = new KfeWalletEntity();
        KfeTransactionResponse response = transactionResponse();

        when(walletResolver.resolveInternalDestinationReference(request)).thenReturn(request);
        when(idempotencyUseCase.requestHash(userId, request)).thenReturn("request-hash");
        when(idempotencyUseCase.find(userId, request.idempotencyKey())).thenReturn(null);
        when(idempotencyUseCase.reserve(userId, request, "request-hash")).thenReturn(idempotency);
        when(transactionRepository.save(any(KfeTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(walletResolver.resolveSourceWallet(userId, request)).thenReturn(sourceWallet);
        when(walletResolver.requiresSourceReserve(request)).thenReturn(true);
        when(pricingService.quote(KfeRail.ONCHAIN, KfeDirection.OUTBOUND, 100_000L, 1_000L))
                .thenReturn(new KfePricingService.Quote(100_000L, 100_000L, 1_000L, 101_900L, 900L));
        when(hashService.sha256(anyString())).thenReturn("proposal-hash");
        stubPassingGate();
        when(outboxUseCase.enqueueExternal(any(), any())).thenReturn(UUID.randomUUID());
        when(responseMapper.toTransactionResponse(any(KfeTransactionEntity.class))).thenReturn(response);

        KfeTransactionResponse result = useCase.submit(userId, request);

        assertSame(response, result);
        var transactionCaptor = org.mockito.ArgumentCaptor.forClass(KfeTransactionEntity.class);
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).save(transactionCaptor.capture());
        KfeTransactionEntity transaction = transactionCaptor.getValue();
        assertThat(transaction.getExternalReference())
                .isEqualTo("bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh");
        assertThat(transaction.getMemo()).isEqualTo("memo");
        // On-chain stays async — no sync drain.
        verify(outboxProcessor, never()).process(any());
    }

    @Test
    void lightningOutboundDrainsOutboxSynchronouslyAfterCommit() {
        Long userId = 123L;
        UUID outboxId = UUID.randomUUID();
        UUID sourceWalletId = UUID.randomUUID();
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "ln-idemp",
                KfeRail.LIGHTNING,
                KfeDirection.OUTBOUND,
                sourceWalletId,
                null,
                5_000L,
                100L,
                "lntb50u1p...",
                "ln pay",
                "totp",
                "passkey",
                null);
        KfeIdempotencyEntity idempotency = new KfeIdempotencyEntity();
        KfeWalletEntity sourceWallet = new KfeWalletEntity();
        sourceWallet.setId(sourceWalletId);
        KfeTransactionResponse pendingResponse = transactionResponse();
        KfeTransactionResponse settledResponse = transactionResponse();

        when(walletResolver.resolveInternalDestinationReference(request)).thenReturn(request);
        when(idempotencyUseCase.requestHash(userId, request)).thenReturn("ln-hash");
        when(idempotencyUseCase.find(userId, request.idempotencyKey())).thenReturn(null);
        when(idempotencyUseCase.reserve(userId, request, "ln-hash")).thenReturn(idempotency);
        when(transactionRepository.save(any(KfeTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(walletResolver.resolveSourceWallet(userId, request)).thenReturn(sourceWallet);
        when(walletResolver.requiresSourceReserve(request)).thenReturn(true);
        when(pricingService.quote(KfeRail.LIGHTNING, KfeDirection.OUTBOUND, 5_000L, 100L))
                .thenReturn(new KfePricingService.Quote(5_000L, 5_000L, 100L, 5_100L, 0L));
        when(hashService.sha256(anyString())).thenReturn("ln-proposal");
        stubPassingGate();
        when(outboxUseCase.enqueueExternal(any(), any())).thenReturn(outboxId);
        when(responseMapper.toTransactionResponse(any(KfeTransactionEntity.class)))
                .thenReturn(pendingResponse, settledResponse);
        when(outboxService.claimImmediate(eq(outboxId), anyString())).thenReturn(true);
        when(transactionRepository.findById(any(UUID.class))).thenAnswer(invocation -> {
            KfeTransactionEntity settled = new KfeTransactionEntity();
            settled.setStatus(source.kfe.model.KfeTransactionStatus.SETTLED);
            settled.setRail(KfeRail.LIGHTNING);
            return Optional.of(settled);
        });

        KfeTransactionResponse result = useCase.submit(userId, request);

        assertSame(settledResponse, result);
        verify(outboxService).claimImmediate(eq(outboxId), anyString());
        verify(outboxProcessor).process(outboxId);
        verify(lightningLiquidityService).reserveForTransaction(any(UUID.class), eq(5_100L));
    }

    @Test
    void settlesAndLinksInternalPaymentRequestInTheSameSubmission() {
        Long userId = 123L;
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        String publicId = "public-internal-id";
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "internal-idemp-key",
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                sourceWalletId,
                destinationWalletId,
                10_000L,
                0L,
                null,
                "payment request",
                null,
                "passkey-json",
                null,
                "123456",
                publicId);
        KfeIdempotencyEntity idempotency = new KfeIdempotencyEntity();
        KfePaymentRequestEntity paymentRequest = new KfePaymentRequestEntity();
        KfeWalletEntity sourceWallet = new KfeWalletEntity();
        KfeWalletEntity destinationWallet = new KfeWalletEntity();
        destinationWallet.setUserId(456L);
        KfeTransactionResponse response = transactionResponse();

        when(walletResolver.resolveInternalDestinationReference(request)).thenReturn(request);
        when(idempotencyUseCase.requestHash(userId, request)).thenReturn("internal-request-hash");
        when(idempotencyUseCase.reserve(userId, request, "internal-request-hash")).thenReturn(idempotency);
        when(paymentRequestSettlementUseCase.lockAndValidate(request)).thenReturn(paymentRequest);
        when(transactionRepository.save(any(KfeTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(walletResolver.resolveSourceWallet(userId, request)).thenReturn(sourceWallet);
        when(walletResolver.resolveDestinationWallet(userId, request)).thenReturn(destinationWallet);
        when(walletResolver.requiresSourceReserve(request)).thenReturn(true);
        when(pricingService.quote(KfeRail.INTERNAL, KfeDirection.INTERNAL, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 10_000L, 90L));
        when(hashService.sha256(anyString())).thenReturn("internal-proposal-hash");
        stubPassingGate();
        when(responseMapper.toTransactionResponse(any(KfeTransactionEntity.class))).thenReturn(response);

        KfeTransactionResponse result = useCase.submit(userId, request);

        assertSame(response, result);
        var transactionCaptor = org.mockito.ArgumentCaptor.forClass(KfeTransactionEntity.class);
        verify(paymentRequestSettlementUseCase).markPaid(org.mockito.ArgumentMatchers.eq(paymentRequest),
                transactionCaptor.capture());
        assertThat(transactionCaptor.getValue().getExternalReference()).isEqualTo(publicId);
    }

    private KfeSubmitTransactionRequest outboundRequest() {
        return new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                UUID.randomUUID(),
                null,
                100_000L,
                1000L,
                "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                "memo",
                "totp-code-123",
                "passkey-json",
                "passphrase"
        );
    }

    private KfeTransactionResponse transactionResponse() {
        return mock(KfeTransactionResponse.class);
    }
}
