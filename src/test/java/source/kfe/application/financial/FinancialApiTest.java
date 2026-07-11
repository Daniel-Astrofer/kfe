package source.kfe.application.financial;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.service.KfePricingService;
import source.kfe.service.KfeResponseMapper;
import source.kfe.service.KfeTransactionEngine;
import source.kfe.service.KfeWalletNetworkService;
import source.kfe.service.KfeWalletService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialApiTest {

    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfeResponseMapper responseMapper = mock(KfeResponseMapper.class);
    private final FinancialApi api = new FinancialApi(
            mock(KfeTransactionEngine.class),
            transactionRepository,
            responseMapper,
            mock(KfePricingService.class),
            mock(KfeWalletService.class),
            mock(KfeWalletNetworkService.class));

    @Test
    void listsOnlyParticipantVisibleTransactionsWithBoundedPagination() {
        Long userId = 42L;
        KfeTransactionEntity transaction = new KfeTransactionEntity();
        KfeTransactionResponse response = mock(KfeTransactionResponse.class);
        when(transactionRepository.findParticipantVisibleByUserId(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(KfeRail.INTERNAL),
                org.mockito.ArgumentMatchers.eq(KfeDirection.INTERNAL),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(transaction));
        when(responseMapper.toTransactionResponse(transaction, userId)).thenReturn(response);

        assertThat(api.transactions(userId, -5, 1_000)).containsExactly(response);

        verify(transactionRepository).findParticipantVisibleByUserId(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(KfeRail.INTERNAL),
                org.mockito.ArgumentMatchers.eq(KfeDirection.INTERNAL),
                argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 200));
    }

    @Test
    void getsInternalReceiverTransactionThroughParticipantScopedQuery() {
        Long receiverUserId = 20L;
        UUID transactionId = UUID.randomUUID();
        KfeTransactionEntity transaction = new KfeTransactionEntity();
        KfeTransactionResponse response = mock(KfeTransactionResponse.class);
        when(transactionRepository.findParticipantVisibleById(
                transactionId, receiverUserId, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.of(transaction));
        when(responseMapper.toTransactionResponse(transaction, receiverUserId)).thenReturn(response);

        assertThat(api.transaction(receiverUserId, transactionId)).isSameAs(response);
    }

    @Test
    void hidesTransactionsOutsideTheAuthenticatedUsersParticipation() {
        Long userId = 99L;
        UUID transactionId = UUID.randomUUID();
        when(transactionRepository.findParticipantVisibleById(
                transactionId, userId, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> api.transaction(userId, transactionId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KFE transaction not found.");
    }
}
