package source.kfe.application.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import source.common.financial.FinancialTickerPort;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.model.KfeIdempotencyEntity;
import source.kfe.model.KfeDirection;
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
            feeSettlementService
    );

    @Test
    void duplicateIdempotencyReservationReturnsExistingTransactionResponse() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = outboundRequest();
        String requestHash = "request-hash";
        when(walletResolver.resolveInternalDestinationReference(request)).thenReturn(request);
        KfeTransactionResponse existingResponse = transactionResponse();

        when(idempotencyUseCase.requestHash(userId, request)).thenReturn(requestHash);
        when(idempotencyUseCase.find(userId, request.idempotencyKey())).thenReturn(null);
        when(idempotencyUseCase.reserve(userId, request, requestHash))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency reservation"));
        when(idempotencyUseCase.getExistingByIdempotency(userId, request.idempotencyKey(), requestHash))
                .thenReturn(existingResponse);

        KfeTransactionResponse response = useCase.submit(userId, request);

        assertSame(existingResponse, response);
        verify(idempotencyUseCase).getExistingByIdempotency(userId, request.idempotencyKey(), requestHash);
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
