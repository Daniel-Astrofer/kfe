package source.kfe.application.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import source.common.financial.FinancialTickerPort;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.model.KfeIdempotencyEntity;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.service.KfeBalanceService;
import source.kfe.service.KfeDashboardPublisher;
import source.kfe.service.KfeFeeSettlementService;
import source.kfe.service.KfeHashService;
import source.kfe.service.KfePricingService;
import source.kfe.service.KfeQuorumGateway;
import source.kfe.service.KfeResponseMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeSubmitTransactionUseCaseTest {

    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfePricingService pricingService = mock(KfePricingService.class);
    private final FinancialTickerPort tickerPort = mock(FinancialTickerPort.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeQuorumGateway quorumGateway = mock(KfeQuorumGateway.class);
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

    private final KfeSubmitTransactionUseCase useCase = new KfeSubmitTransactionUseCase(
            transactionRepository,
            pricingService,
            tickerPort,
            balanceService,
            quorumGateway,
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
            paymentRequestSettlementUseCase
    );

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
        when(quorumGateway.requireHealthyUnanimousConsensus("proposal-hash"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));
        when(responseMapper.toTransactionResponse(any(KfeTransactionEntity.class))).thenReturn(response);

        KfeTransactionResponse result = useCase.submit(userId, request);

        assertSame(response, result);
        var transactionCaptor = org.mockito.ArgumentCaptor.forClass(KfeTransactionEntity.class);
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).save(transactionCaptor.capture());
        KfeTransactionEntity transaction = transactionCaptor.getValue();
        assertThat(transaction.getExternalReference())
                .isEqualTo("bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh");
        assertThat(transaction.getMemo()).isEqualTo("memo");
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
        when(quorumGateway.requireHealthyUnanimousConsensus("internal-proposal-hash"))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));
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
