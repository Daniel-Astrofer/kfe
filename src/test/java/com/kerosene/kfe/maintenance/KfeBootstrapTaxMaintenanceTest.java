package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.controller.KfeMaintenanceAdminController;
import com.kerosene.kfe.dto.KfeClassifyTaxEventRequest;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeTaxEventClassificationEntity;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.repository.KfeTaxEventClassificationRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.runtime.KfeBitcoinRuntimeBootstrap;
import com.kerosene.kfe.service.KfeSystemWalletService;
import com.kerosene.kfe.service.KfeTaxEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeBootstrapTaxMaintenanceTest {
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfeSystemWalletService wallets = mock(KfeSystemWalletService.class);
    private final BitcoinCoreRpcClient rpc = mock(BitcoinCoreRpcClient.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeTaxEventClassificationRepository classifications = mock(KfeTaxEventClassificationRepository.class);
    private final KfeTaxEventService tax = new KfeTaxEventService(transactions, classifications);

    enum Rejection { UNINJECTED, DRAINING, STORAGE_OUTAGE }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @ParameterizedTest
    @EnumSource(Rejection.class)
    void bootstrapRejectionPausesBeforeAllWalletAndRpcEffects(Rejection rejection) {
        KfeBitcoinRuntimeBootstrap bootstrap = bootstrap(true);
        if (rejection != Rejection.UNINJECTED) {
            bootstrap.setMaintenanceGuard(guard);
            reject(rejection);
        }
        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
        verifyNoInteractions(wallets, rpc);
        verify(store, never()).resolve(any(), anyBoolean());
        verify(store, never()).transition(any(), any(), anyLong());
        if (rejection == Rejection.UNINJECTED) {
            verifyNoInteractions(store);
        } else {
            verify(store).admit("bitcoin.bootstrap");
        }
    }

    @ParameterizedTest
    @EnumSource(value = Rejection.class, names = {"DRAINING", "STORAGE_OUTAGE"})
    void pausedSpringStartupRetainsAdminStatusAndRefusesReadiness(Rejection rejection) {
        reject(rejection);
        if (rejection == Rejection.STORAGE_OUTAGE) {
            when(store.observe()).thenThrow(new IllegalStateException("synthetic observation storage outage"));
        } else {
            when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                    new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.DRAINING, "bootstrap-test", 1),
                    Instant.now(), Map.of()));
        }
        SpringApplication application = new SpringApplication(BootstrapContext.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("maintenanceGuard", guard);
            context.getBeanFactory().registerSingleton("systemWalletService", wallets);
            context.getBeanFactory().registerSingleton("bitcoinCoreRpcClient", rpc);
        });
        try (ConfigurableApplicationContext context = application.run(
                "--spring.main.banner-mode=off",
                "--spring.config.location=optional:classpath:/bootstrap-maintenance-test.properties")) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBean(ApplicationAvailabilityBean.class).getReadinessState())
                    .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            assertThat(context.getBean(ApplicationAvailabilityBean.class).getLivenessState())
                    .isEqualTo(LivenessState.CORRECT);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            assertThat(context.getBean(ApplicationAvailabilityBean.class).getReadinessState())
                    .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            var admin = UsernamePasswordAuthenticationToken.authenticated("7", "synthetic",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            var status = context.getBean(KfeMaintenanceAdminController.class).status(admin);
            assertThat(status.safeToUpdate()).isFalse();
            assertThat(status.blockers()).containsEntry("mutationCoverageUnknown", 1L)
                    .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
            if (rejection == Rejection.STORAGE_OUTAGE) {
                assertThat(status.mode()).isNull();
                assertThat(status.blockers()).containsEntry("observationUnavailable", 1L);
            } else {
                assertThat(status.mode()).isEqualTo(KfeMaintenanceGuard.Mode.DRAINING);
            }
            verifyNoInteractions(wallets, rpc);
            verify(store, never()).transition(any(), any(), anyLong());

            // Model externally restored admission, then explicitly retry in this host.
            doReturn(admission).when(store).admit(anyString());
            systemWalletsReady();
            rpcReady();
            context.getBean(KfeBitcoinRuntimeBootstrap.class).run(null);
            assertThat(context.getBean(ApplicationAvailabilityBean.class).getReadinessState())
                    .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            assertThat(context.getBean(ApplicationAvailabilityBean.class).getReadinessState())
                    .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
            verify(store).resolve(admission.id(), false);
            verify(store, never()).transition(any(), any(), anyLong());
        }
    }

    @Test
    void pausedBootstrapCanRetryAfterAdmissionIsRestoredWithoutForcingActive() {
        KfeBitcoinRuntimeBootstrap bootstrap = bootstrap(false);
        bootstrap.setMaintenanceGuard(guard);
        reject(Rejection.DRAINING);
        bootstrap.run(null);
        verifyNoInteractions(wallets, rpc);
        reset(store);
        active();
        systemWalletsReady();
        bootstrap.run(null);
        verify(wallets).ensureSystemWallets();
        verify(store).admit("bitcoin.bootstrap");
        verify(store).resolve(admission.id(), true);
        verify(store, never()).transition(any(), any(), anyLong());
        verifyNoInteractions(rpc);
    }

    @Test
    void successfulRpcWalletLoadingRemainsUncertainEvenAfterCommit() {
        active();
        systemWalletsReady();
        rpcReady();
        KfeBitcoinRuntimeBootstrap bootstrap = bootstrap(true);
        bootstrap.setMaintenanceGuard(guard);
        bindTransaction();
        bootstrap.run(null);
        verify(rpc).ensureWalletLoaded("primary");
        verify(rpc).ensureWalletLoaded("funds");
        verify(rpc).ensureWalletLoaded("profit");
        verify(store, never()).resolve(any(), anyBoolean());
        finish(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
    }

    @Test
    void unknownRpcFailureIsNotCaughtAsAStartupAdmissionPause() {
        active();
        systemWalletsReady();
        rpcReady();
        doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "unknown remote result"))
                .when(rpc).ensureWalletLoaded("primary");
        KfeBitcoinRuntimeBootstrap bootstrap = bootstrap(true);
        bootstrap.setMaintenanceGuard(guard);
        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class)
                .hasMessageContaining("unknown remote result");
        verify(store).resolve(admission.id(), false);
        verify(rpc, never()).ensureWalletLoaded("funds");
        verify(rpc, never()).ensureWalletLoaded("profit");
    }

    @Test
    void missingRequiredRpcClientAndLocalFailureRemainUncertain() {
        active();
        systemWalletsReady();
        KfeBitcoinRuntimeBootstrap bootstrap = new KfeBitcoinRuntimeBootstrap(wallets,
                new DefaultListableBeanFactory().getBeanProvider(BitcoinCoreRpcClient.class),
                true, true, true, false, true, "testnet", "primary", "funds", "profit");
        bootstrap.setMaintenanceGuard(guard);
        assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RPC client is unavailable");
        verify(store).resolve(admission.id(), false);
        reset(store, wallets);
        active();
        when(wallets.ensureSystemWallets()).thenThrow(new IllegalStateException("wallet commit failed"));
        KfeBitcoinRuntimeBootstrap localBootstrap = bootstrap(false);
        localBootstrap.setMaintenanceGuard(guard);
        assertThatThrownBy(() -> localBootstrap.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wallet commit failed");
        verify(store).resolve(admission.id(), false);
        verifyNoInteractions(rpc);
    }

    @Test
    void nestedBootstrapCanFinishAfterDrainButASeparateRootPauses() {
        active();
        systemWalletsReady();
        rpcReady();
        KfeBitcoinRuntimeBootstrap bootstrap = bootstrap(true);
        bootstrap.setMaintenanceGuard(guard);
        guard.executeMutation("parent", () -> {
            reject(Rejection.DRAINING);
            bootstrap.run(null);
            return true;
        });
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        verify(rpc).ensureWalletLoaded("profit");
        clearInvocations(wallets, rpc);
        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
        verifyNoInteractions(wallets, rpc);
        verify(store, times(2)).admit(anyString());
    }

    @ParameterizedTest
    @EnumSource(Rejection.class)
    void taxRejectionKeepsExistingClassificationUnchanged(Rejection rejection) {
        KfeTransactionEntity transaction = ownedTransaction();
        KfeTaxEventClassificationEntity existing = classification(transaction);
        when(classifications.findByUserIdAndEventId(7L, transaction.getId().toString()))
                .thenReturn(Optional.of(existing));
        if (rejection != Rejection.UNINJECTED) {
            tax.setMaintenanceGuard(guard);
            reject(rejection);
        }
        assertThatThrownBy(() -> classify(transaction)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThat(existing.getClassification()).isEqualTo("ORIGINAL");
        assertThat(existing.getUserId()).isEqualTo(7L);
        assertThat(existing.getEventId()).isEqualTo(transaction.getId().toString());
        verifyNoInteractions(classifications);
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Rejection.class)
    void benignTaxListAndBothExportsStayAvailableWithoutAdmission(Rejection rejection) {
        if (rejection != Rejection.UNINJECTED) {
            tax.setMaintenanceGuard(guard);
            reject(rejection);
        }
        KfeTransactionEntity transaction = transaction();
        when(transactions.findTop200ByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(transaction));
        when(classifications.findByUserId(7L)).thenReturn(List.of(classification(transaction)));
        var event = tax.list(7L).getFirst();
        assertThat(event.eventType()).isEqualTo("WITHDRAWAL");
        assertThat(event.quantitySats()).isEqualTo(90L);
        assertThat(event.classification()).isEqualTo("ORIGINAL");
        assertThat(event.sourceRef()).isEqualTo("synthetic-txid");
        assertThat(event.walletId()).isEqualTo(transaction.getSourceWalletId());
        assertThat(tax.export(7L, "csv").content()).contains("id,eventType,asset,quantitySats", "\"ORIGINAL\"");
        assertThat(tax.export(7L, "json").content()).contains("\"quantitySats\":90");
        verifyNoInteractions(store);
        verify(classifications, never()).save(any());
    }

    @Test
    void ownershipAndInputChecksRemainAheadOfTaxMutation() {
        tax.setMaintenanceGuard(guard);
        UUID foreignId = UUID.randomUUID();
        when(transactions.findByIdAndUserId(foreignId, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tax.classify(7L, foreignId.toString(), new KfeClassifyTaxEventRequest("income")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> tax.classify(7L, "bad-id", new KfeClassifyTaxEventRequest("income")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tax.classify(7L, foreignId.toString(), new KfeClassifyTaxEventRequest(" ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("classification is required");
        verifyNoInteractions(store, classifications);
        verify(transactions).findByIdAndUserId(foreignId, 7L);
    }

    @ParameterizedTest
    @ValueSource(ints = {TransactionSynchronization.STATUS_COMMITTED,
            TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_UNKNOWN})
    void taxCompletionWaitsForTheActualTransactionOutcome(int completion) {
        active();
        tax.setMaintenanceGuard(guard);
        KfeTransactionEntity transaction = ownedTransaction();
        when(classifications.findByUserIdAndEventId(7L, transaction.getId().toString()))
                .thenReturn(Optional.empty());
        bindTransaction();
        assertThat(classify(transaction).classification()).isEqualTo("INCOME");
        verify(classifications).save(argThat(entity -> entity.getUserId().equals(7L)
                && entity.getEventId().equals(transaction.getId().toString())
                && entity.getClassification().equals("INCOME")));
        verify(store, never()).resolve(any(), anyBoolean());
        finish(completion);
        verify(store).resolve(admission.id(), completion == TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void nestedTaxMutationUsesParentAdmissionDuringDrainAndPreservesFailedSaveUncertainty() {
        active();
        tax.setMaintenanceGuard(guard);
        KfeTransactionEntity transaction = ownedTransaction();
        when(classifications.findByUserIdAndEventId(7L, transaction.getId().toString()))
                .thenReturn(Optional.empty());
        guard.executeMutation("parent", () -> {
            reject(Rejection.DRAINING);
            return classify(transaction);
        });
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), true);
        clearInvocations(classifications);
        assertThatThrownBy(() -> classify(transaction)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(classifications);
        reset(store);
        active();
        when(classifications.save(any())).thenThrow(new IllegalStateException("unknown save outcome"));
        bindTransaction();
        guard.executeMutation("parent", () -> {
            reject(Rejection.DRAINING);
            assertThatThrownBy(() -> classify(transaction)).isInstanceOf(IllegalStateException.class);
            return true;
        });
        finish(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
        verify(store, times(1)).admit(anyString());
    }

    private void active() {
        when(store.admit(anyString())).thenReturn(admission);
    }

    private void reject(Rejection rejection) {
        if (rejection == Rejection.STORAGE_OUTAGE) {
            doThrow(new IllegalStateException("synthetic admission storage outage")).when(store).admit(anyString());
        } else {
            doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "DRAINING")).when(store).admit(anyString());
        }
    }

    private KfeBitcoinRuntimeBootstrap bootstrap(boolean rpcEnabled) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("bitcoinCoreRpcClient", rpc);
        return new KfeBitcoinRuntimeBootstrap(wallets, beans.getBeanProvider(BitcoinCoreRpcClient.class),
                rpcEnabled, true, true, false, true, "testnet", "primary", "funds", "profit");
    }

    private void systemWalletsReady() {
        when(wallets.ensureSystemWallets()).thenReturn(
                new KfeSystemWalletService.SystemWallets(UUID.randomUUID(), UUID.randomUUID()));
    }

    private void rpcReady() {
        when(rpc.chain()).thenReturn("test");
        when(rpc.blockchainInfo()).thenReturn(new ObjectMapper().createObjectNode().put("initialblockdownload", false));
    }

    private KfeTransactionEntity transaction() {
        KfeTransactionEntity transaction = new KfeTransactionEntity();
        transaction.setUserId(7L);
        transaction.setDirection(KfeDirection.OUTBOUND);
        transaction.setGrossAmountSats(100L);
        transaction.setReceiverAmountSats(90L);
        transaction.setSourceWalletId(UUID.randomUUID());
        transaction.setBlockchainTxid("synthetic-txid");
        return transaction;
    }

    private KfeTransactionEntity ownedTransaction() {
        KfeTransactionEntity transaction = transaction();
        when(transactions.findByIdAndUserId(transaction.getId(), 7L)).thenReturn(Optional.of(transaction));
        return transaction;
    }

    private KfeTaxEventClassificationEntity classification(KfeTransactionEntity transaction) {
        KfeTaxEventClassificationEntity classification = new KfeTaxEventClassificationEntity();
        classification.setUserId(7L);
        classification.setEventId(transaction.getId().toString());
        classification.setClassification("ORIGINAL");
        return classification;
    }

    private com.kerosene.kfe.dto.KfeTaxEventResponse classify(KfeTransactionEntity transaction) {
        return tax.classify(7L, transaction.getId().toString(), new KfeClassifyTaxEventRequest(" income "));
    }

    private void bindTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finish(int completion) {
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(completion));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BootstrapContext {
        @Bean
        ApplicationAvailabilityBean applicationAvailability() {
            return new ApplicationAvailabilityBean();
        }

        @Bean
        KfeBitcoinRuntimeBootstrap bitcoinBootstrap(KfeSystemWalletService wallets, ObjectProvider<BitcoinCoreRpcClient> rpc) {
            return new KfeBitcoinRuntimeBootstrap(wallets, rpc,
                    true, true, true, false, true, "testnet", "primary", "funds", "profit");
        }

        @Bean
        KfeMaintenanceAdminController maintenanceAdmin(KfeMaintenanceGuard guard) {
            return new KfeMaintenanceAdminController(guard);
        }
    }
}
