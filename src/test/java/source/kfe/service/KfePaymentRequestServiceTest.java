package source.kfe.service;

import org.junit.jupiter.api.Test;
import source.common.financial.FinancialNotificationPort;
import source.common.service.AddressDerivationService;
import source.kfe.dto.KfeCreatePaymentRequest;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfeBalanceMovementRepository;
import source.kfe.repository.KfePaymentRequestRepository;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfePaymentRequestServiceTest {

    private final KfePaymentRequestRepository paymentRequestRepository = mock(KfePaymentRequestRepository.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final KfeWalletService walletService = mock(KfeWalletService.class);
    private final AddressDerivationService addressDerivationService = mock(AddressDerivationService.class);
    private final KfeReceiveAddressIssuer receiveAddressIssuer = mock(KfeReceiveAddressIssuer.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);

    // New dependencies mocked
    private final source.kfe.repository.KfeTransactionRepository transactionRepository = mock(source.kfe.repository.KfeTransactionRepository.class);
    private final KfeBalanceMovementRepository movementRepository = mock(KfeBalanceMovementRepository.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final FinancialNotificationPort notificationPort = mock(FinancialNotificationPort.class);

    private final KfePaymentRequestService service = new KfePaymentRequestService(
            paymentRequestRepository,
            transactionRepository,
            movementRepository,
            walletRepository,
            addressRepository,
            walletService,
            addressDerivationService,
            receiveAddressIssuer,
            auditLogService,
            balanceService,
            statementService,
            dashboardPublisher,
            notificationPort,
            false);

    @Test
    void publicGetExpiresOverdueOpenRequestBeforeReturningIt() {
        KfePaymentRequestEntity paymentRequest = paymentRequest();
        paymentRequest.setStatus(KfePaymentRequestStatus.OPEN);
        paymentRequest.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(paymentRequestRepository.findByPublicId("public-id")).thenReturn(Optional.of(paymentRequest));
        when(paymentRequestRepository.save(paymentRequest)).thenReturn(paymentRequest);

        var response = service.publicGet("public-id");

        assertThat(response.status()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        assertThat(paymentRequest.getStatus()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        verify(paymentRequestRepository).save(paymentRequest);
    }

    @Test
    void createAllowsWatchOnlyWalletWithXpubAndFreshAddress() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setUserId(7L);
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setXpub("xpub-watch-only");
        wallet.setLastDerivedIndex(-1);

        when(walletRepository.findByIdAndUserId(walletId, 7L)).thenReturn(Optional.of(wallet));
        when(addressDerivationService.deriveAddressDetailsFromXpub("xpub-watch-only", 0))
                .thenReturn(new AddressDerivationService.DerivedAddress("bcrt1qwatchonly", new byte[]{1}, 0, false));
        when(walletRepository.save(wallet)).thenReturn(wallet);
        when(addressRepository.save(any(KfeWalletAddressEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRequestRepository.findByPublicId(anyString())).thenReturn(Optional.empty());
        when(paymentRequestRepository.save(any(KfePaymentRequestEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(7L, new KfeCreatePaymentRequest(
                walletId,
                KfeRail.ONCHAIN,
                10_000L,
                null,
                null,
                null,
                null,
                true));

        assertThat(response.walletId()).isEqualTo(walletId);
        assertThat(response.address()).isEqualTo("bcrt1qwatchonly");
        assertThat(wallet.getLastDerivedIndex()).isZero();
        verify(walletService, never()).rotateAddress(7L, walletId);
        verify(receiveAddressIssuer, never()).issue(anyString());
    }

    @Test
    void cancelDoesNotRegressExpiredPaymentRequest() {
        UUID id = UUID.randomUUID();
        KfePaymentRequestEntity paymentRequest = paymentRequest();
        paymentRequest.setStatus(KfePaymentRequestStatus.EXPIRED);
        when(paymentRequestRepository.findByIdAndUserId(id, 7L)).thenReturn(Optional.of(paymentRequest));

        var response = service.cancel(7L, id);

        assertThat(response.status()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        verify(paymentRequestRepository, never()).save(paymentRequest);
        verify(auditLogService, never()).record(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    private KfePaymentRequestEntity paymentRequest() {
        KfePaymentRequestEntity paymentRequest = new KfePaymentRequestEntity();
        paymentRequest.setPublicId("public-id");
        paymentRequest.setUserId(7L);
        paymentRequest.setWalletId(UUID.randomUUID());
        paymentRequest.setAddressId(UUID.randomUUID());
        paymentRequest.setAddress("bcrt1qpaymentrequest");
        return paymentRequest;
    }
}
