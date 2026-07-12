package source.kfe.application.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.jpa.repository.Lock;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.repository.KfePaymentRequestRepository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KfeInternalPaymentRequestSettlementUseCaseTest {

    private final KfePaymentRequestRepository repository = mock(KfePaymentRequestRepository.class);
    private final KfeInternalPaymentRequestSettlementUseCase useCase =
            new KfeInternalPaymentRequestSettlementUseCase(repository);

    @Test
    void ignoresInternalTransfersWithoutPaymentRequestReference() {
        assertThat(useCase.lockAndValidate(request(null, UUID.randomUUID(), 10_000L))).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void locksAndValidatesOpenInternalPaymentRequest() {
        UUID destinationWalletId = UUID.randomUUID();
        KfePaymentRequestEntity paymentRequest = paymentRequest(destinationWalletId, 10_000L);
        when(repository.findByPublicIdForUpdate("public-id")).thenReturn(Optional.of(paymentRequest));

        KfePaymentRequestEntity result =
                useCase.lockAndValidate(request(" public-id ", destinationWalletId, 10_000L));

        assertSame(paymentRequest, result);
        verify(repository).findByPublicIdForUpdate("public-id");
    }

    @ParameterizedTest
    @EnumSource(value = KfePaymentRequestStatus.class, names = "OPEN", mode = EnumSource.Mode.EXCLUDE)
    void rejectsTerminalPaymentRequestReuse(KfePaymentRequestStatus terminalStatus) {
        UUID destinationWalletId = UUID.randomUUID();
        KfePaymentRequestEntity paymentRequest = paymentRequest(destinationWalletId, 10_000L);
        paymentRequest.setStatus(terminalStatus);
        when(repository.findByPublicIdForUpdate("public-id")).thenReturn(Optional.of(paymentRequest));

        assertThrows(IllegalStateException.class,
                () -> useCase.lockAndValidate(request("public-id", destinationWalletId, 10_000L)));
    }

    @Test
    void repositoryLocksPublicIdToSerializeConcurrentPayments() throws Exception {
        Lock lock = KfePaymentRequestRepository.class
                .getMethod("findByPublicIdForUpdate", String.class)
                .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void rejectsPaymentRequestFromAnotherRail() {
        UUID destinationWalletId = UUID.randomUUID();
        KfePaymentRequestEntity paymentRequest = paymentRequest(destinationWalletId, 10_000L);
        paymentRequest.setRail(KfeRail.ONCHAIN);
        when(repository.findByPublicIdForUpdate("public-id")).thenReturn(Optional.of(paymentRequest));

        assertThrows(IllegalArgumentException.class,
                () -> useCase.lockAndValidate(request("public-id", destinationWalletId, 10_000L)));
    }

    @Test
    void rejectsMismatchedDestinationAndAmount() {
        UUID destinationWalletId = UUID.randomUUID();
        KfePaymentRequestEntity paymentRequest = paymentRequest(destinationWalletId, 10_000L);
        when(repository.findByPublicIdForUpdate("public-id")).thenReturn(Optional.of(paymentRequest));

        assertThrows(IllegalArgumentException.class,
                () -> useCase.lockAndValidate(request("public-id", UUID.randomUUID(), 10_000L)));
        assertThrows(IllegalArgumentException.class,
                () -> useCase.lockAndValidate(request("public-id", destinationWalletId, 9_999L)));
    }

    @Test
    void rejectsExpiredPaymentRequest() {
        UUID destinationWalletId = UUID.randomUUID();
        KfePaymentRequestEntity paymentRequest = paymentRequest(destinationWalletId, 10_000L);
        paymentRequest.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(repository.findByPublicIdForUpdate("public-id")).thenReturn(Optional.of(paymentRequest));

        assertThrows(IllegalStateException.class,
                () -> useCase.lockAndValidate(request("public-id", destinationWalletId, 10_000L)));
    }

    @Test
    void marksSettledTransactionAsPaid() {
        KfePaymentRequestEntity paymentRequest = paymentRequest(UUID.randomUUID(), 10_000L);
        KfeTransactionEntity transaction = new KfeTransactionEntity();
        transaction.setStatus(KfeTransactionStatus.SETTLED);

        useCase.markPaid(paymentRequest, transaction);

        assertThat(paymentRequest.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        assertThat(paymentRequest.getPaidTransactionId()).isEqualTo(transaction.getId());
        verify(repository).save(paymentRequest);
    }

    @Test
    void refusesToMarkUnsettledTransactionAsPaid() {
        KfePaymentRequestEntity paymentRequest = paymentRequest(UUID.randomUUID(), 10_000L);
        KfeTransactionEntity transaction = new KfeTransactionEntity();
        transaction.setStatus(KfeTransactionStatus.LOCKED);

        assertThrows(IllegalStateException.class, () -> useCase.markPaid(paymentRequest, transaction));
        verify(repository, never()).save(paymentRequest);
    }

    private KfePaymentRequestEntity paymentRequest(UUID destinationWalletId, Long amountSats) {
        KfePaymentRequestEntity paymentRequest = new KfePaymentRequestEntity();
        paymentRequest.setPublicId("public-id");
        paymentRequest.setUserId(456L);
        paymentRequest.setWalletId(destinationWalletId);
        paymentRequest.setAddress("kerosene:wallet:" + destinationWalletId);
        paymentRequest.setRail(KfeRail.INTERNAL);
        paymentRequest.setStatus(KfePaymentRequestStatus.OPEN);
        paymentRequest.setAmountSats(amountSats);
        return paymentRequest;
    }

    private KfeSubmitTransactionRequest request(String publicId, UUID destinationWalletId, long amountSats) {
        return new KfeSubmitTransactionRequest(
                "idempotency-key",
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                UUID.randomUUID(),
                destinationWalletId,
                amountSats,
                0L,
                null,
                "payment request",
                null,
                "passkey-json",
                null,
                "123456",
                publicId);
    }
}
