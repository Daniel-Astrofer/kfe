package com.kerosene.kfe.service;

import org.junit.jupiter.api.Test;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.dto.KfeCreatePaymentRequest;
import com.kerosene.kfe.model.KfePaymentRequestEntity;
import com.kerosene.kfe.model.KfePaymentRequestStatus;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;
import com.kerosene.kfe.rail.LightningInvoiceGateway;
import com.kerosene.kfe.repository.KfePaymentRequestRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KfePaymentRequestServiceTest {

    private final KfePaymentRequestRepository paymentRequestRepository = mock(KfePaymentRequestRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final KfeWalletService walletService = mock(KfeWalletService.class);
    private final AddressDerivationService addressDerivationService = mock(AddressDerivationService.class);
    private final KfeReceiveAddressIssuer receiveAddressIssuer = mock(KfeReceiveAddressIssuer.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);

    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final LightningInvoiceGateway lightningInvoiceGateway = mock(LightningInvoiceGateway.class);
    private final KfeTransactionCancellationService transactionCancellationService =
            mock(KfeTransactionCancellationService.class);

    private final KfePaymentRequestService service = new KfePaymentRequestService(
            paymentRequestRepository,
            transactionRepository,
            walletRepository,
            addressRepository,
            walletService,
            addressDerivationService,
            receiveAddressIssuer,
            auditLogService,
            dashboardPublisher,
            lightningInvoiceGateway,
            transactionCancellationService);

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
    void createIssuesFreshAddressForCustodialWalletWithoutRotatingWallet() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setUserId(7L);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setLastDerivedIndex(-1);

        when(walletRepository.findByIdAndUserId(walletId, 7L)).thenReturn(Optional.of(wallet));
        when(receiveAddressIssuer.issue("kfe-payment-request-" + walletId))
                .thenReturn(new KfeReceiveAddressIssuer.IssuedAddress(
                        "bcrt1qissued",
                        "bitcoin-core-wallet:kfe",
                        -1,
                        "KFE_BITCOIN_CORE_WALLET"));
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
        assertThat(response.address()).isEqualTo("bcrt1qissued");
        assertThat(wallet.getLastDerivedIndex()).isEqualTo(-1);
        verify(walletService, never()).rotateAddress(7L, walletId);
        verify(receiveAddressIssuer).issue("kfe-payment-request-" + walletId);
    }

    @Test
    void createInternalRequestUsesStructuredWalletReferenceWithoutBitcoinAddress() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setUserId(7L);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ACTIVE);

        when(walletRepository.findByIdAndUserId(walletId, 7L)).thenReturn(Optional.of(wallet));
        when(paymentRequestRepository.findByPublicId(anyString())).thenReturn(Optional.empty());
        when(paymentRequestRepository.save(any(KfePaymentRequestEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(7L, new KfeCreatePaymentRequest(
                walletId,
                KfeRail.INTERNAL,
                10_000L,
                "Internal payment",
                null,
                null,
                null,
                null));

        assertThat(response.walletId()).isEqualTo(walletId);
        assertThat(response.rail()).isEqualTo(KfeRail.INTERNAL);
        assertThat(response.addressId()).isNull();
        assertThat(response.address()).isEqualTo("kerosene:wallet:" + walletId);
        verifyNoInteractions(addressRepository, addressDerivationService, receiveAddressIssuer, walletService);
    }

    @Test
    void cancelDelegatesToCancellationService() {
        UUID id = UUID.randomUUID();
        KfePaymentRequestEntity paymentRequest = paymentRequest();
        paymentRequest.setStatus(KfePaymentRequestStatus.CANCELLED);
        when(transactionCancellationService.cancelPaymentRequest(7L, id)).thenReturn(paymentRequest);

        var response = service.cancel(7L, id);

        assertThat(response.status()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        verify(transactionCancellationService).cancelPaymentRequest(7L, id);
    }

    @Test
    void getExposesObservedValidatingTransactionBeforePaymentRequestIsPaid() {
        UUID id = UUID.randomUUID();
        KfePaymentRequestEntity paymentRequest = paymentRequest();
        paymentRequest.setStatus(KfePaymentRequestStatus.OPEN);
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        tx.setBlockchainTxid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        tx.setConfirmations(0);
        tx.setGrossAmountSats(11_000L);
        tx.setReceiverAmountSats(10_901L);

        when(paymentRequestRepository.findByIdAndUserId(id, 7L)).thenReturn(Optional.of(paymentRequest));
        when(transactionRepository.findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(
                "payment-request:" + paymentRequest.getId() + ":")).thenReturn(Optional.of(tx));

        var response = service.get(7L, id);

        assertThat(response.status()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertThat(response.settlementStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
        assertThat(response.blockchainTxid()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(response.confirmations()).isZero();
        assertThat(response.grossAmountSats()).isEqualTo(11_000L);
        assertThat(response.receiverAmountSats()).isEqualTo(10_901L);
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
