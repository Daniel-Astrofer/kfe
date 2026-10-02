package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.controller.KfeMaintenanceAdminController;
import com.kerosene.kfe.controller.KfePublicPaymentRequestController;
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
import com.kerosene.kfe.rail.CustodyGateway;
import com.kerosene.kfe.rail.LightningInvoiceGateway;
import com.kerosene.kfe.repository.KfePaymentRequestRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeDashboardPublisher;
import com.kerosene.kfe.service.KfeLightningLiquidityService;
import com.kerosene.kfe.service.KfePaymentRequestService;
import com.kerosene.kfe.service.KfePlatformLightningPolicy;
import com.kerosene.kfe.service.KfeReceiveAddressIssuer;
import com.kerosene.kfe.service.KfeResponseMapper;
import com.kerosene.kfe.service.KfeStatementService;
import com.kerosene.kfe.service.KfeTransactionCancellationService;
import com.kerosene.kfe.service.KfeWalletService;
import com.kerosene.kfe.webhook.KfeWebhookDeliveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KfePaymentRequestMaintenanceTest {
    private enum ReadRoot { PUBLIC, OWNER, LIST }
    private enum CommandRoot { EXPIRE, HIDE, CANCEL }

    private static final long USER = 7;
    private static final String PUBLIC_ID = "resolved-public-capability";
    private static final String HASH = "d31a96775a6480dfa1be203a17d6c904c32706b7257c2e1781b99d797001b731";
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addresses = mock(KfeWalletAddressRepository.class);
    private final KfeWalletService walletService = mock(KfeWalletService.class);
    private final AddressDerivationService derivation = mock(AddressDerivationService.class);
    private final KfeReceiveAddressIssuer issuer = mock(KfeReceiveAddressIssuer.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final LightningInvoiceGateway invoices = mock(LightningInvoiceGateway.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeLightningLiquidityService liquidity = mock(KfeLightningLiquidityService.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeWebhookDeliveryService webhooks = mock(KfeWebhookDeliveryService.class);
    // Actual cancellation code, so drain tests check its real financial effect boundaries.
    private final KfeTransactionCancellationService cancellation = new KfeTransactionCancellationService(
            transactions, requests, balances, liquidity, statements, mapper, dashboard, audit, invoices);
    private final KfePaymentRequestService service = newService();
    private final KfePaymentRequestEntity payment = spy(new KfePaymentRequestEntity());
    private final KfeWalletEntity wallet = new KfeWalletEntity();

    @BeforeEach
    void activeResolvedFixtures() {
        SecurityContextHolder.clearContext();
        service.setMaintenanceGuard(guard);
        cancellation.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenReturn(admission);
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(USER);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setSpendable(true);
        wallet.setLastDerivedIndex(-1);
        payment.setUserId(USER);
        payment.setPublicId(PUBLIC_ID);
        payment.setWalletId(wallet.getId());
        payment.setAddress("bcrt1qexisting");
        payment.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        when(wallets.findByIdAndUserId(wallet.getId(), USER)).thenReturn(Optional.of(wallet));
        when(requests.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(payment));
        when(requests.findByIdAndUserId(payment.getId(), USER)).thenReturn(Optional.of(payment));
        when(requests.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(payment));
        when(requests.save(any(KfePaymentRequestEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @ParameterizedTest
    @EnumSource(value = KfeRail.class, names = {"ONCHAIN", "LIGHTNING", "INTERNAL"})
    void drainingCreateCannotIssueAnAddressInvoiceOrWriteAnyFinancialData(KfeRail rail) {
        draining();
        assertDraining(() -> service.create(USER, createCommand(rail)));
        assertNoFinancialEffects();
        assertThat(wallet.getLastDerivedIndex()).isEqualTo(-1);
        verify(store).admit("payment-request.create");
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void drainingFreshXpubCreationCannotEvenAdvanceTheManagedWallet() {
        wallet.setXpub("xpub-fixture");
        draining();
        assertDraining(() -> service.create(USER, createCommand(KfeRail.ONCHAIN)));
        assertThat(wallet.getLastDerivedIndex()).isEqualTo(-1);
        assertNoFinancialEffects();
    }

    @ParameterizedTest
    @EnumSource(CommandRoot.class)
    void drainingExplicitCommandsCannotDirtyTheEntityOrRunCancellationEffects(CommandRoot root) {
        payment.setRail(KfeRail.LIGHTNING);
        payment.setPaymentHash(HASH);
        draining();
        assertDraining(() -> command(root));
        assertThat(payment.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertThat(payment.getHiddenAt()).isNull();
        assertThat(payment.getCancelledAt()).isNull();
        verify(payment, never()).expire();
        verify(payment, never()).hide();
        verify(payment, never()).cancel();
        assertNoFinancialEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(ReadRoot.class)
    void drainingDueReadsResolveCapabilityButCannotMarkOrSaveExpired(ReadRoot root) {
        overdue();
        draining();
        assertDraining(() -> read(root));
        assertThat(payment.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(payment, never()).expire();
        assertNoFinancialEffects();
        verify(store).admit("payment-request.expiry-on-read");
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(ReadRoot.class)
    void benignUnexpiredReadsNeedNoAdmissionWhileDraining(ReadRoot root) {
        draining();
        assertThat(read(root)).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertNoFinancialEffects();
        verifyNoInteractions(store);
    }

    @Test
    void mixedListCanReadBenignEntryButNeverExpiresItsDueEntryDuringDrain() {
        KfePaymentRequestEntity due = spy(new KfePaymentRequestEntity());
        due.setUserId(USER);
        due.setPublicId("second-resolved-capability");
        due.setWalletId(wallet.getId());
        due.setAddress("bcrt1qdue");
        due.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(requests.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(payment, due));
        draining();
        assertDraining(() -> service.list(USER));
        assertThat(payment.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertThat(due.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(payment, never()).expire();
        verify(due, never()).expire();
        verify(store).admit("payment-request.expiry-on-read");
        assertNoFinancialEffects();
    }

    @Test
    void publicReadWithoutADeadlineRemainsPureWhileDraining() {
        payment.setExpiresAt(null);
        draining();
        assertThat(service.publicGet(PUBLIC_ID).status()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verifyNoInteractions(store);
        assertNoFinancialEffects();
    }

    @Test
    void publicCapabilityAndSettlementResolutionPrecedeExpiryAdmission() {
        overdue();
        draining();
        assertDraining(() -> service.publicGet(PUBLIC_ID));
        var order = inOrder(requests, transactions, store);
        order.verify(requests).findByPublicId(PUBLIC_ID);
        order.verify(transactions).findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(settlementKey());
        order.verify(store).admit("payment-request.expiry-on-read");
        verify(requests, never()).save(any());
    }

    @Test
    void invalidPublicOrOwnerCapabilitiesDoNotObtainAdmissionOrLeakAMaintenanceDecision() {
        draining();
        assertThatThrownBy(() -> service.publicGet("unresolved-capability"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request not found.");
        assertThatThrownBy(() -> service.get(USER + 1, payment.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.expire(USER + 1, payment.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.hide(USER + 1, payment.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(USER + 1, createCommand(KfeRail.ONCHAIN)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
        assertNoFinancialEffects();
    }

    @Test
    void dueRequestWithAnObservedSettlementStaysOpenWithoutAdmission() {
        overdue();
        KfeTransactionEntity settlement = new KfeTransactionEntity();
        settlement.setStatus(KfeTransactionStatus.VALIDATING);
        when(transactions.findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(settlementKey()))
                .thenReturn(Optional.of(settlement));
        draining();
        assertThat(service.publicGet(PUBLIC_ID).status()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertThat(service.get(USER, payment.getId()).settlementStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
        verifyNoInteractions(store);
        assertNoFinancialEffects();
    }

    @Test
    void linkedSettlementLookupKeepsItsExistingPaidTransactionIdPrecedence() {
        overdue();
        UUID transactionId = UUID.randomUUID();
        payment.setPaidTransactionId(transactionId);
        when(transactions.findById(transactionId)).thenReturn(Optional.of(new KfeTransactionEntity()));
        draining();
        assertThat(service.publicGet(PUBLIC_ID).status()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(transactions).findById(transactionId);
        verify(transactions, never()).findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(anyString());
        verifyNoInteractions(store);
        assertNoFinancialEffects();
    }

    @ParameterizedTest
    @EnumSource(value = KfePaymentRequestStatus.class, names = {"PAID", "EXPIRED", "HIDDEN", "CANCELLED", "FAILED"})
    void terminalPublicReadsStayObservationalEvenWhenDeadlineHasPassed(KfePaymentRequestStatus status) {
        overdue();
        payment.setStatus(status);
        draining();
        assertThat(service.publicGet(PUBLIC_ID).status()).isEqualTo(status);
        verifyNoInteractions(store);
        assertNoFinancialEffects();
    }

    @Test
    void activeAnonymousExpirySavesOnceAndDuplicateReadDoesNotReadmit() {
        overdue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(service.publicGet(PUBLIC_ID).status()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        assertThat(service.publicGet(PUBLIC_ID).status()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        verify(payment).expire();
        verify(requests).save(payment);
        verify(store).admit("payment-request.expiry-on-read");
        verify(store).resolve(admission.id(), true);
        verifyNoInteractions(audit, dashboard, invoices, webhooks);
    }

    @Test
    void duplicateEntriesInAnOwnedListDoNotRepeatAnExpiryMutation() {
        overdue();
        when(requests.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(payment, payment));
        assertThat(service.list(USER)).allSatisfy(response ->
                assertThat(response.status()).isEqualTo(KfePaymentRequestStatus.EXPIRED));
        verify(payment).expire();
        verify(requests).save(payment);
        verify(store).admit("payment-request.expiry-on-read");
    }

    @Test
    void anonymousPublicControllerAndActualInvoiceHashPolicyPreservePureReadsDuringDrain() throws Exception {
        draining();
        when(requests.findFirstByPaymentHashIgnoreCase(HASH)).thenReturn(Optional.of(payment));
        MockMvc mvc = publicMvc();
        mvc.perform(get("/api/public/kfe/payment-requests/" + PUBLIC_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("data.status").value("OPEN"));
        mvc.perform(get("/api/public/kfe/payment-requests/lookup").param("invoice", HASH))
                .andExpect(status().isOk()).andExpect(jsonPath("data.publicId").value(PUBLIC_ID));
        verify(requests).findFirstByPaymentHashIgnoreCase(HASH);
        verifyNoInteractions(store);
        assertNoFinancialEffects();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anonymousDuePublicAndInvoiceLookupReturn503WithoutAnyWriteDuringDrain() throws Exception {
        overdue();
        draining();
        when(requests.findFirstByPaymentHashIgnoreCase(HASH)).thenReturn(Optional.of(payment));
        MockMvc mvc = publicMvc();
        mvc.perform(get("/api/public/kfe/payment-requests/" + PUBLIC_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("schema").value(KfeMaintenanceGuard.SCHEMA));
        mvc.perform(get("/api/public/kfe/payment-requests/lookup").param("invoice", HASH))
                .andExpect(status().isServiceUnavailable());
        assertThat(payment.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(payment, never()).expire();
        assertNoFinancialEffects();
        verify(store, times(2)).admit("payment-request.expiry-on-read");
    }

    @Test
    void activeAnonymousInvoiceLookupUsesExistingResolutionThenExpires() {
        overdue();
        when(requests.findFirstByPaymentHashIgnoreCase(HASH)).thenReturn(Optional.of(payment));
        KfePlatformLightningPolicy policy = new KfePlatformLightningPolicy(requests, service);
        assertThat(policy.resolvePlatformInvoice(HASH)).get().satisfies(response ->
                assertThat(response.status()).isEqualTo(KfePaymentRequestStatus.EXPIRED));
        var order = inOrder(requests, store);
        order.verify(requests).findFirstByPaymentRequestIgnoreCase(HASH);
        order.verify(requests).findFirstByPaymentHashIgnoreCase(HASH);
        order.verify(requests).findByPublicId(PUBLIC_ID);
        order.verify(store).admit("payment-request.expiry-on-read");
        order.verify(requests).save(payment);
        verify(store).resolve(admission.id(), true);
    }

    @Test
    void unavailableConstructorDefaultRejectsWritesButStillAllowsPurePublicReads() throws Exception {
        KfePaymentRequestService uninjected = newService();
        assertThat(KfePaymentRequestService.class.getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class)
                .getAnnotation(Autowired.class).required()).isTrue();
        assertThat(uninjected.publicGet(PUBLIC_ID).status()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertDraining(() -> uninjected.create(USER, createCommand(KfeRail.INTERNAL)));
        assertDraining(() -> uninjected.expire(USER, payment.getId()));
        assertDraining(() -> uninjected.hide(USER, payment.getId()));
        assertDraining(() -> uninjected.cancel(USER, payment.getId()));
        overdue();
        assertDraining(() -> uninjected.publicGet(PUBLIC_ID));
        assertNoFinancialEffects();
        verifyNoInteractions(store);
    }

    @Test
    void missingAdmissionStoreCannotDirtyAnOverdueManagedEntity() {
        overdue();
        when(store.admit(anyString())).thenThrow(new IllegalStateException("database unavailable"));
        assertDraining(() -> service.publicGet(PUBLIC_ID));
        assertThat(payment.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertNoFinancialEffects();
    }

    @Test
    void publicResolutionCannotRacePastADrainBeforeAdmission() {
        overdue();
        when(requests.findByPublicId(PUBLIC_ID)).thenAnswer(invocation -> {
            draining(); // Drain begins after capability resolution, before the write boundary.
            return Optional.of(payment);
        });
        assertDraining(() -> service.publicGet(PUBLIC_ID));
        assertThat(payment.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertNoFinancialEffects();
    }

    @Test
    void expiryAdmittedBeforeDrainMayFinishButNextMutationNeedsNewAdmission() {
        overdue();
        AtomicBoolean draining = new AtomicBoolean();
        when(store.admit(anyString())).thenAnswer(invocation -> {
            if (draining.getAndSet(true)) {
                throw new KfeMaintenanceGuard.MaintenanceException(503, "draining");
            }
            return admission; // Drain starts immediately after this successful admission.
        });
        assertThat(service.publicGet(PUBLIC_ID).status()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        assertDraining(() -> service.hide(USER, payment.getId()));
        assertThat(payment.getStatus()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        assertThat(payment.getHiddenAt()).isNull();
        verify(requests).save(payment);
        verify(store).resolve(admission.id(), true);
    }

    @Test
    void synchronousAlreadyAdmittedParentCanFinishNestedExpiryDuringDrainAndCleansUp() {
        overdue();
        guard.executeMutation("test.already-admitted", () -> {
            draining();
            return service.publicGet(PUBLIC_ID);
        });
        verify(store, times(1)).admit(anyString());
        verify(requests).save(payment);
        verify(store).resolve(admission.id(), true);
        assertDraining(() -> service.hide(USER, payment.getId()));
        assertThat(payment.getHiddenAt()).isNull();
        verify(store, times(2)).admit(anyString());
    }

    @Test
    void expiryCompletionWaitsForObservedCommitAndRollbackRemainsUncertain() {
        overdue();
        bindTransaction();
        service.publicGet(PUBLIC_ID);
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void expiryCompletesOnlyAfterCommitAndNoPendingContinuationIsInvented() {
        overdue();
        bindTransaction();
        service.publicGet(PUBLIC_ID);
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), true);
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
    }

    @Test
    void expirySaveFailureStaysUncertainAndGuardStateDoesNotLeakToNextCall() {
        overdue();
        when(requests.save(payment)).thenThrow(new IllegalStateException("persistence failed"));
        assertThatThrownBy(() -> service.publicGet(PUBLIC_ID)).isInstanceOf(IllegalStateException.class);
        verify(store).resolve(admission.id(), false);
        draining();
        assertDraining(() -> service.hide(USER, payment.getId()));
        verify(store, times(2)).admit(anyString());
        assertThat(payment.getHiddenAt()).isNull();
    }

    @Test
    void explicitExpireAndHideKeepTheirOriginalConditionsAndAuditBehavior() {
        assertThat(service.expire(USER, payment.getId()).status()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        assertThat(service.expire(USER, payment.getId()).status()).isEqualTo(KfePaymentRequestStatus.EXPIRED);
        verify(requests).save(payment);
        verify(audit).record(eq("KFE_PAYMENT_REQUEST_EXPIRED"), isNull(), eq(wallet.getId()),
                isNull(), isNull(), anyMap());
        payment.setStatus(KfePaymentRequestStatus.PAID);
        assertThat(service.hide(USER, payment.getId()).status()).isEqualTo(KfePaymentRequestStatus.PAID);
        verify(payment, never()).hide();
        payment.setStatus(KfePaymentRequestStatus.OPEN);
        assertThat(service.hide(USER, payment.getId()).status()).isEqualTo(KfePaymentRequestStatus.HIDDEN);
        verify(audit).record(eq("KFE_PAYMENT_REQUEST_HIDDEN"), isNull(), eq(wallet.getId()),
                isNull(), isNull(), anyMap());
    }

    @Test
    void internalCreateIsLocalButExternalInvoiceCreationDoesNotCertifyRemoteCompletion() {
        assertThat(service.create(USER, createCommand(KfeRail.INTERNAL)).rail()).isEqualTo(KfeRail.INTERNAL);
        verify(store).resolve(admission.id(), true);
        when(invoices.isLive()).thenReturn(true);
        when(invoices.createLightningInvoice(any())).thenReturn(new CustodyGateway.GeneratedLightningInvoice(
                "lnbcrt1fixture", HASH, null, null, null));
        assertThat(service.create(USER, createCommand(KfeRail.LIGHTNING)).paymentHash()).isEqualTo(HASH);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void addressIssuanceKeepsItsFinancialBehaviorAndCompletionConservative() {
        when(issuer.issue(anyString())).thenReturn(new KfeReceiveAddressIssuer.IssuedAddress(
                "bcrt1qissued", "m/84h/0h/0h/0/0", 0, "fixture-provider"));
        when(addresses.save(any(KfeWalletAddressEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.create(USER, createCommand(KfeRail.ONCHAIN)).address()).isEqualTo("bcrt1qissued");
        assertThat(wallet.getLastDerivedIndex()).isZero();
        verify(wallets).save(wallet);
        verify(addresses).save(any(KfeWalletAddressEntity.class));
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void realCancellationPreservesDuplicateBehaviorButProviderFailureCannotBecomeCertain() {
        payment.setRail(KfeRail.LIGHTNING);
        payment.setPaymentHash(HASH);
        when(invoices.cancelLightningInvoice(any())).thenThrow(new IllegalStateException("provider timeout"));
        assertThat(service.cancel(USER, payment.getId()).status()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        assertThat(service.cancel(USER, payment.getId()).status()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        verify(payment).cancel();
        verify(requests).save(payment);
        verify(invoices).cancelLightningInvoice(any());
        verify(dashboard).publishAfterCommit(USER);
        verify(store, times(2)).resolve(admission.id(), false);
    }

    @Test
    void successfulLocalCancelStillCannotCertifyItsPostCommitPublication() {
        bindTransaction();
        service.cancel(USER, payment.getId());
        verify(dashboard).publishAfterCommit(USER);
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void thisBoundedServicePatchDoesNotRemoveAnyUnknownCoverageBlocker() {
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.DRAINING, "update", 1),
                Instant.now(), Map.of()));
        assertThat(guard.status().safeToUpdate()).isFalse();
        assertThat(guard.status().blockers()).containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
    }

    private KfePaymentRequestService newService() {
        return new KfePaymentRequestService(requests, transactions, wallets, addresses, walletService,
                derivation, issuer, audit, dashboard, invoices, cancellation, new ObjectMapper(), webhooks);
    }

    private KfeCreatePaymentRequest createCommand(KfeRail rail) {
        return new KfeCreatePaymentRequest(wallet.getId(), rail, null, 10_000L,
                "fixture", null, null, null, true, null);
    }

    private Object read(ReadRoot root) {
        return switch (root) {
            case PUBLIC -> service.publicGet(PUBLIC_ID);
            case OWNER -> service.get(USER, payment.getId());
            case LIST -> service.list(USER);
        };
    }

    private Object command(CommandRoot root) {
        return switch (root) {
            case EXPIRE -> service.expire(USER, payment.getId());
            case HIDE -> service.hide(USER, payment.getId());
            case CANCEL -> service.cancel(USER, payment.getId());
        };
    }

    private MockMvc publicMvc() {
        return MockMvcBuilders.standaloneSetup(new KfePublicPaymentRequestController(service,
                        new KfePlatformLightningPolicy(requests, service)))
                .setControllerAdvice(new KfeMaintenanceAdminController.MaintenanceErrors()).build();
    }

    private String settlementKey() {
        return "payment-request:" + payment.getId() + ":";
    }

    private void overdue() {
        payment.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    }

    private void draining() {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
    }

    private void assertNoFinancialEffects() {
        verify(requests, never()).save(any());
        verify(wallets, never()).save(any());
        verify(addresses, never()).save(any());
        verify(transactions, never()).save(any());
        verifyNoInteractions(issuer, derivation, invoices, audit, dashboard, balances, liquidity, statements, webhooks);
    }

    private static void assertDraining(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(503));
    }

    private static void bindTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void finishTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(status));
    }
}
