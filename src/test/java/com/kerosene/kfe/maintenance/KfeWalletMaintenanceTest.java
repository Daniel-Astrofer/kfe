package com.kerosene.kfe.maintenance;

import com.kerosene.common.financial.FinancialTransactionApprovalPort;
import com.kerosene.common.financial.FinancialUserDirectoryPort;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import com.kerosene.kfe.dto.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.dto.KfeCreateWalletRequest;
import com.kerosene.kfe.dto.KfeUpdateWalletRequest;
import com.kerosene.kfe.model.KfePsbtWorkflowEntity;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletAddressRole;
import com.kerosene.kfe.model.KfeWalletAddressStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.rail.BlockchainClient;
import com.kerosene.kfe.rail.LightningInvoiceGateway;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual service roots and guard; only durable/provider/repository boundaries are mocked. */
class KfeWalletMaintenanceTest {
    private enum EffectRoot { CREATE, UPDATE, ARCHIVE, ROTATE, ENSURE_ADDRESS, UTXOS, COLD_PSBT }
    private static final long USER = 7L;
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addresses = mock(KfeWalletAddressRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfeQuorumGateway quorum = mock(KfeQuorumGateway.class);
    private final KfeMpcKeyService mpc = mock(KfeMpcKeyService.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final AddressDerivationService derivation = mock(AddressDerivationService.class);
    private final KfeReceiveAddressIssuer issuer = mock(KfeReceiveAddressIssuer.class);
    private final ObjectProvider<VaultMeshSettlementPort> mesh = provider();
    private final ObjectProvider<BitcoinCoreRpcClient> coreProvider = provider();
    private final ObjectProvider<BlockchainClient> chainProvider = provider();
    private final ObjectProvider<KfeOnchainBalanceSyncService> syncProvider = provider();
    private final ObjectProvider<KfeColdWalletObservationService> observationProvider = provider();
    private final ObjectProvider<KfeMonitoredChainAddressIndex> indexProvider = provider();
    private final BitcoinCoreRpcClient core = mock(BitcoinCoreRpcClient.class);
    private final BlockchainClient chain = mock(BlockchainClient.class);
    private final FinancialUserDirectoryPort directory = mock(FinancialUserDirectoryPort.class);
    private final FinancialTransactionApprovalPort approval = mock(FinancialTransactionApprovalPort.class);
    private final KfePsbtWorkflowService psbts = mock(KfePsbtWorkflowService.class);
    private final BitcoinAddressValidator validator = mock(BitcoinAddressValidator.class);
    private final LightningInvoiceGateway invoices = mock(LightningInvoiceGateway.class);
    private final KfeHashService hash = new KfeHashService();
    private final KfeResponseMapper mapper = new KfeResponseMapper(addresses, wallets, provider());
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final TransactionTemplate template = new TransactionTemplate(transactions);
    private final KfeWalletService service = newWalletService();
    private final KfeWalletNetworkService network = newNetworkService();
    private final KfeWalletEntity wallet = spy(new KfeWalletEntity());
    private final List<KfeWalletAddressEntity> activeRows = new ArrayList<>();

    @BeforeEach
    void activeBoundary() {
        service.setMaintenanceGuard(guard);
        network.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenAnswer(invocation -> {
            if (draining.get()) {
                throw new KfeMaintenanceGuard.MaintenanceException(503, "KFE is draining.");
            }
            return admission;
        });
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(USER);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setSpendable(true);
        wallet.setLabel("original");
        wallet.setLastDerivedIndex(-1);
        when(wallets.findByIdAndUserId(wallet.getId(), USER)).thenReturn(Optional.of(wallet));
        when(wallets.findByIdAndUserIdForUpdate(wallet.getId(), USER)).thenReturn(Optional.of(wallet));
        when(wallets.save(any(KfeWalletEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(addresses.findByWalletIdAndStatusOrderByCreatedAtDesc(any(UUID.class),
                eq(KfeWalletAddressStatus.ACTIVE))).thenAnswer(invocation -> activeRows.stream()
                        .filter(row -> row.getWalletId().equals(invocation.getArgument(0)))
                        .filter(row -> row.getStatus() == KfeWalletAddressStatus.ACTIVE).toList());
        when(addresses.save(any(KfeWalletAddressEntity.class))).thenAnswer(invocation -> {
            KfeWalletAddressEntity row = invocation.getArgument(0);
            if (!activeRows.contains(row)) {
                activeRows.add(row);
            }
            return row;
        });
        when(quorum.requireHealthyUnanimousConsensus(anyString())).thenReturn(new KfeQuorumGateway.Result(2, 2));
        when(mpc.keygenWallet(any(UUID.class), eq(USER))).thenReturn("mpc-public-key");
        when(issuer.issue(anyString())).thenReturn(new KfeReceiveAddressIssuer.IssuedAddress(
                "bcrt1qfresh", "test/11", 11, "TEST"));
        when(chainProvider.getIfAvailable()).thenReturn(chain);
        when(coreProvider.getIfAvailable()).thenReturn(core);
        clearInvocations(wallet);
    }

    @AfterEach
    void noLeakedTransactionContext() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(EffectRoot.class)
    void drainRejectsEveryEffectRootBeforeDirtyingEntitiesOrCallingProviders(EffectRoot root) {
        prepareRoot(root);
        draining.set(true);
        assertUnavailable(() -> call(root));
        assertNoFinancialEffects();
        assertThat(wallet.getStatus()).isEqualTo(KfeWalletStatus.ACTIVE);
        assertThat(wallet.getLabel()).isEqualTo("original");
        assertThat(wallet.isSpendable()).isEqualTo(root != EffectRoot.COLD_PSBT);
        assertThat(wallet.getLastDerivedIndex()).isEqualTo(-1);
        verify(wallet, never()).setStatus(any());
        verify(wallet, never()).setLabel(anyString());
        verify(wallet, never()).setSpendable(anyBoolean());
        verify(wallet, never()).setLastDerivedIndex(anyInt());
        verify(store).admit(operation(root));
        verify(store, never()).resolve(any(), anyBoolean());
        assertThat(transactions.commits).isZero();
    }

    @ParameterizedTest
    @EnumSource(EffectRoot.class)
    void manuallyConstructedServicesCannotMutateWithoutMandatoryInjection(EffectRoot root) {
        prepareRoot(root);
        KfeWalletService unconfigured = newWalletService();
        KfeWalletNetworkService unconfiguredNetwork = newNetworkService();
        assertUnavailable(() -> call(root, unconfigured, unconfiguredNetwork));
        assertNoFinancialEffects();
        verifyNoInteractions(store);
    }

    @Test
    void springGuardInjectionIsMandatoryAndNullIsNeverABYPASS() throws Exception {
        for (Class<?> type : List.of(KfeWalletService.class, KfeWalletNetworkService.class)) {
            Autowired injection = type.getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class)
                    .getAnnotation(Autowired.class);
            assertThat(injection).isNotNull();
            assertThat(injection.required()).isTrue();
        }
        assertThatThrownBy(() -> service.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> network.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void admissionStorageFailureUsesSafeEnvelopeAndPerformsNoEffects() {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("jdbc-secret-fixture"));
        assertThatThrownBy(() -> service.createWallet(USER, createCommand(KfeWalletKind.CUSTODIAL_ONCHAIN)))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class)
                .hasMessage("KFE maintenance admission is unavailable.");
        assertNoFinancialEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void existingAddressAndArchivedNoopArePureReadsDuringDrain() {
        addAddress(KfeWalletAddressRole.RECEIVE, "  bcrt1qexisting  ");
        draining.set(true);
        assertThat(service.ensureActiveReceiveAddress(USER, wallet.getId())).isEqualTo("bcrt1qexisting");
        wallet.setStatus(KfeWalletStatus.ARCHIVED);
        assertThat(service.archiveWallet(USER, wallet.getId()).status()).isEqualTo(KfeWalletStatus.ARCHIVED);
        assertNoFinancialEffects();
        verifyNoInteractions(store);
    }

    @Test
    void benignWalletListNamesAndReceivingCapabilitiesNeedNoAdmission() {
        draining.set(true);
        when(directory.findByUsername("alice")).thenReturn(Optional.of(
                new FinancialUserDirectoryPort.FinancialUserHandle(USER, "alice", true)));
        when(wallets.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(wallet));
        when(wallets.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER), anyList())).thenReturn(List.of(wallet));
        assertThat(service.listWallets(USER)).hasSize(1);
        assertThat(service.availableWalletNames()).isNotEmpty();
        assertThat(network.receivingCapabilities("@alice")).isNotNull();
        assertThat(network.receivingCapabilities(USER, "@alice")).isNotNull();
        assertNoFinancialEffects();
        verifyNoInteractions(store);
    }

    @Test
    void xpubReadCannotAdvanceIndexAndRotationCannotRetireAddressesDuringDrain() {
        wallet.setXpub("tpub-fixture");
        draining.set(true);
        assertUnavailable(() -> service.ensureActiveReceiveAddress(USER, wallet.getId()));
        KfeWalletAddressEntity row = spy(addAddress(KfeWalletAddressRole.RECEIVE, "bcrt1qexisting"));
        activeRows.clear();
        activeRows.add(row);
        assertUnavailable(() -> service.rotateAddress(USER, wallet.getId()));
        assertThat(wallet.getLastDerivedIndex()).isEqualTo(-1);
        assertThat(row.getStatus()).isEqualTo(KfeWalletAddressStatus.ACTIVE);
        verify(row, never()).retire();
        assertNoFinancialEffects();
    }

    @Test
    void ownershipAndWalletKindAreStillCheckedBeforeReadOrPsbtAdmission() {
        UUID foreignId = UUID.randomUUID();
        assertThatThrownBy(() -> network.createColdWalletPsbt(USER, foreignId, psbtCommand()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE wallet not found.");
        assertThatThrownBy(() -> service.ensureActiveReceiveAddress(USER, foreignId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE wallet not found.");
        assertThatThrownBy(() -> network.listUtxos(USER, foreignId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE wallet not found.");
        assertThatThrownBy(() -> network.createColdWalletPsbt(USER, wallet.getId(), psbtCommand()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("WATCH_ONLY");
        verifyNoInteractions(store);
        assertNoFinancialEffects();
    }

    @Test
    void systemWalletBootstrapKindsRemainRejectedWithoutAdmission() {
        for (KfeWalletKind kind : List.of(KfeWalletKind.SYSTEM_FUNDS, KfeWalletKind.SYSTEM_PROFIT)) {
            assertThatThrownBy(() -> service.createWallet(USER, createCommand(kind)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("runtime bootstrap");
        }
        verifyNoInteractions(store);
        assertNoFinancialEffects();
    }

    @Test
    void drainBetweenOwnerResolutionAndAdmissionCannotCreateColdChangeOrPsbt() {
        coldWallet();
        when(wallets.findByIdAndUserId(wallet.getId(), USER)).thenAnswer(invocation -> {
            draining.set(true);
            return Optional.of(wallet);
        });
        assertUnavailable(() -> network.createColdWalletPsbt(USER, wallet.getId(), psbtCommand()));
        assertNoFinancialEffects();
        verify(store).admit("wallet.create-cold-psbt");
    }

    @Test
    void admittedCreationFinishesKeygenAndActivationAfterDrainWithoutSecondAdmission() {
        AtomicReference<KfeWalletEntity> created = prepareCreation();
        when(quorum.requireHealthyUnanimousConsensus(anyString())).thenAnswer(invocation -> {
            draining.set(true);
            return new KfeQuorumGateway.Result(2, 2);
        });
        var result = service.createWallet(USER, createCommand(KfeWalletKind.CUSTODIAL_ONCHAIN));
        assertThat(result.status()).isEqualTo(KfeWalletStatus.ACTIVE);
        assertThat(created.get().getMpcPublicKey()).isEqualTo("mpc-public-key");
        assertThat(transactions.commits).isEqualTo(2);
        verify(mpc).keygenWallet(created.get().getId(), USER);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertUnavailable(() -> service.rotateAddress(USER, created.get().getId()));
        verify(store, times(2)).admit(anyString());
    }

    @Test
    void admittedCreationStillPersistsFailureMarkerAfterQuorumFailsDuringDrain() {
        AtomicReference<KfeWalletEntity> created = prepareCreation();
        when(quorum.requireHealthyUnanimousConsensus(anyString())).thenAnswer(invocation -> {
            draining.set(true);
            throw new IllegalStateException("quorum interrupted");
        });
        assertThatThrownBy(() -> service.createWallet(USER, createCommand(KfeWalletKind.CUSTODIAL_ONCHAIN)))
                .hasMessageContaining("quorum is temporarily unavailable");
        assertThat(created.get().getStatus()).isEqualTo(KfeWalletStatus.QUORUM_BLOCKED);
        assertThat(transactions.commits).isEqualTo(2);
        verifyNoInteractions(mpc);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void admittedRotationRetiresIssuesAndCompletesAfterDrain() {
        KfeWalletAddressEntity old = addAddress(KfeWalletAddressRole.RECEIVE, "bcrt1qold");
        when(quorum.requireHealthyUnanimousConsensus(anyString())).thenAnswer(invocation -> {
            draining.set(true);
            return new KfeQuorumGateway.Result(2, 2);
        });
        var result = service.rotateAddress(USER, wallet.getId());
        assertThat(result.address()).isEqualTo("bcrt1qfresh");
        assertThat(old.getStatus()).isEqualTo(KfeWalletAddressStatus.RETIRED);
        assertThat(wallet.getStatus()).isEqualTo(KfeWalletStatus.ACTIVE);
        assertThat(wallet.getLastDerivedIndex()).isEqualTo(11);
        assertThat(transactions.commits).isEqualTo(2);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void parentAdmittedWorkflowCanFinishBothWalletServicesWithOnlyOneAdmission() {
        coldWallet();
        // Existing receiving address is absent: this exercises the effectful read root.
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setXpub(null);
        preparePsbtProviders();
        var result = guard.executeMutation("parent.submission", () -> {
            draining.set(true);
            assertThat(service.ensureActiveReceiveAddress(USER, wallet.getId())).isEqualTo("bcrt1qfresh");
            coldWallet();
            return network.createColdWalletPsbt(USER, wallet.getId(), psbtCommand());
        });
        assertThat(result.psbt()).isEqualTo("psbt-fixture");
        verify(store).admit("parent.submission");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        verify(approval).approveColdWalletPsbt(USER, "totp-fixture");
        verify(issuer).issue(anyString());
        assertThat(activeRows).anyMatch(row -> row.getAddressRole() == KfeWalletAddressRole.CHANGE);
        // The next independent request must not inherit that parent's admission.
        assertUnavailable(() -> network.listUtxos(USER, wallet.getId()));
        verify(store, times(2)).admit(anyString());
    }

    @Test
    void successfulColdPsbtHasOneRootEvenThoughItCallsGuardedUtxoRead() {
        coldWallet();
        addAddress(KfeWalletAddressRole.RECEIVE, "bcrt1qinput");
        preparePsbtProviders();
        var response = network.createColdWalletPsbt(USER, wallet.getId(), psbtCommand());
        assertThat(response.psbt()).isEqualTo("psbt-fixture");
        assertThat(response.inputs()).containsExactly(new KfeColdWalletPsbtRequest.Input("txid-owned", 0));
        verify(store).admit("wallet.create-cold-psbt");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        verify(approval).approveColdWalletPsbt(USER, "totp-fixture");
    }

    @Test
    void approvalFailureLeavesUncertaintyAndNeverDerivesOrScans() {
        coldWallet();
        doThrow(new IllegalArgumentException("invalid totp"))
                .when(approval).approveColdWalletPsbt(USER, "totp-fixture");
        assertThatThrownBy(() -> network.createColdWalletPsbt(USER, wallet.getId(), psbtCommand()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid totp");
        verifyNoInteractions(chain, core, derivation, psbts);
        verify(addresses, never()).save(any());
        verify(store).resolve(admission.id(), false);
        draining.set(true);
        assertUnavailable(() -> network.listUtxos(USER, wallet.getId()));
        verify(store, times(2)).admit(anyString());
    }

    @Test
    void swallowedDescriptorScanFailureAndEvenSuccessfulScansStayUncertain() {
        coldWallet();
        wallet.setDescriptor("wpkh(tpub-fixture/0/*)");
        when(chain.getUnspentOutputsFromScan(wallet.getDescriptor(), 200))
                .thenThrow(new IllegalStateException("remote scan status unknown"));
        assertThat(network.listUtxos(USER, wallet.getId())).isEmpty();
        verify(chain).getUnspentOutputsFromScan(wallet.getDescriptor(), 200);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void addressReusePreservesDuplicateBindingAlgorithmWithoutExtraAdmission() {
        KfeWalletAddressEntity old = addAddress(KfeWalletAddressRole.RECEIVE, "bcrt1qfresh");
        old.retire();
        when(addresses.findFirstByAddressIgnoreCase("bcrt1qfresh")).thenReturn(Optional.of(old));
        assertThat(service.ensureActiveReceiveAddress(USER, wallet.getId())).isEqualTo("bcrt1qfresh");
        assertThat(activeRows).hasSize(1);
        assertThat(old.getStatus()).isEqualTo(KfeWalletAddressStatus.ACTIVE);
        assertThat(old.getRetiredAt()).isNull();
        verify(addresses).save(old);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void realSpringTransactionDefersResolutionUntilCommitAndKeepsCallbackUncertainty() {
        template.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            service.updateWallet(USER, wallet.getId(), new KfeUpdateWalletRequest("updated"));
            verify(store, never()).resolve(any(), anyBoolean());
            assertThat(wallet.getLabel()).isEqualTo("updated");
        });
        assertThat(transactions.commits).isEqualTo(1);
        verify(store).resolve(admission.id(), false);
        draining.set(true);
        assertUnavailable(() -> service.updateWallet(USER, wallet.getId(), new KfeUpdateWalletRequest("later")));
        assertThat(wallet.getLabel()).isEqualTo("updated");
    }

    @Test
    void realSpringRollbackCannotManufactureCertainCompletion() {
        template.executeWithoutResult(status -> {
            service.archiveWallet(USER, wallet.getId());
            verify(store, never()).resolve(any(), anyBoolean());
            status.setRollbackOnly();
        });
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(store).resolve(admission.id(), false);
        // Mock repositories do not model DB rollback; this checks synchronization, not durability.
    }

    @Test
    void partialWalletCoverageDoesNotRemoveAnyOfTheThreeUnknownBlockers() {
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.DRAINING, "wallet-wave", 1),
                java.time.Instant.now(), java.util.Map.of()));
        var status = guard.status();
        assertThat(status.safeToUpdate()).isFalse();
        assertThat(status.blockers()).containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
    }

    private void prepareRoot(EffectRoot root) {
        if (root == EffectRoot.COLD_PSBT) {
            coldWallet();
        }
        clearInvocations(wallet);
    }

    private Object call(EffectRoot root) {
        return call(root, service, network);
    }

    private Object call(EffectRoot root, KfeWalletService walletService, KfeWalletNetworkService networkService) {
        return switch (root) {
            case CREATE -> walletService.createWallet(USER, createCommand(KfeWalletKind.CUSTODIAL_ONCHAIN));
            case UPDATE -> walletService.updateWallet(USER, wallet.getId(), new KfeUpdateWalletRequest("updated"));
            case ARCHIVE -> walletService.archiveWallet(USER, wallet.getId());
            case ROTATE -> walletService.rotateAddress(USER, wallet.getId());
            case ENSURE_ADDRESS -> walletService.ensureActiveReceiveAddress(USER, wallet.getId());
            case UTXOS -> networkService.listUtxos(USER, wallet.getId());
            case COLD_PSBT -> networkService.createColdWalletPsbt(USER, wallet.getId(), psbtCommand());
        };
    }

    private String operation(EffectRoot root) {
        return switch (root) {
            case CREATE -> "wallet.create";
            case UPDATE -> "wallet.update";
            case ARCHIVE -> "wallet.archive";
            case ROTATE -> "wallet.rotate-address";
            case ENSURE_ADDRESS -> "wallet.ensure-receive-address";
            case UTXOS -> "wallet.list-utxos";
            case COLD_PSBT -> "wallet.create-cold-psbt";
        };
    }

    private void assertUnavailable(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(503));
    }

    private void assertNoFinancialEffects() {
        verify(wallets, never()).save(any());
        verify(addresses, never()).save(any());
        verify(addresses, never()).saveAll(any());
        verifyNoInteractions(balances, quorum, mpc, audit, dashboard, derivation,
                chain, core, approval, psbts, mesh, syncProvider, observationProvider, indexProvider);
        verify(issuer, never()).issue(anyString());
    }

    private void coldWallet() {
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        wallet.setSpendable(false);
        wallet.setXpub("tpub-fixture");
    }

    private KfeWalletAddressEntity addAddress(KfeWalletAddressRole role, String address) {
        KfeWalletAddressEntity row = new KfeWalletAddressEntity();
        row.setWalletId(wallet.getId());
        row.setAddressRole(role);
        row.setAddress(address);
        row.setStatus(KfeWalletAddressStatus.ACTIVE);
        activeRows.add(row);
        return row;
    }

    private AtomicReference<KfeWalletEntity> prepareCreation() {
        AtomicReference<KfeWalletEntity> created = new AtomicReference<>();
        when(wallets.save(any(KfeWalletEntity.class))).thenAnswer(invocation -> {
            KfeWalletEntity row = invocation.getArgument(0);
            created.set(row);
            return row;
        });
        when(wallets.findByIdAndUserIdForUpdate(any(UUID.class), eq(USER)))
                .thenAnswer(invocation -> Optional.ofNullable(created.get()));
        when(wallets.findById(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(created.get()));
        return created;
    }

    private void preparePsbtProviders() {
        when(validator.isValidBitcoinAddressForConfiguredNetwork("bcrt1qdestination")).thenReturn(true);
        when(chain.getUnspentOutputsMerged(anyString())).thenReturn(List.of(
                new BlockchainClient.AddressUtxo("txid-owned", 0, 50_000L, "0014", 6, "bcrt1qinput")));
        when(derivation.deriveAddressFromXpub("tpub-fixture", 0, true)).thenReturn("bcrt1qchange");
        when(core.createWatchOnlyPsbt(anyList(), eq("bcrt1qdestination"), eq(10_000L),
                eq(6), isNull(), eq("bcrt1qchange")))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("psbt-fixture", 250L));
        UUID walletId = wallet.getId(); // Resolve the spy before registering Mockito argument matchers.
        when(psbts.create(eq(USER), eq(walletId), eq("psbt-fixture"), anyString(),
                eq(250L), eq(10_000L), eq("bcrt1qdestination"), anyList()))
                .thenReturn(new KfePsbtWorkflowEntity());
    }

    private KfeCreateWalletRequest createCommand(KfeWalletKind kind) {
        return new KfeCreateWalletRequest(kind, null, "created", null, null, null, null,
                null, null, null, null, false);
    }

    private KfeColdWalletPsbtRequest psbtCommand() {
        return new KfeColdWalletPsbtRequest("bcrt1qdestination", 10_000L, 6, null,
                List.of(new KfeColdWalletPsbtRequest.Input("txid-owned", 0)), "totp-fixture");
    }

    private KfeWalletService newWalletService() {
        return new KfeWalletService(wallets, addresses, balances, hash, audit, quorum, mpc, mapper,
                dashboard, derivation, issuer, mesh, template, coreProvider, syncProvider,
                observationProvider, indexProvider);
    }

    private KfeWalletNetworkService newNetworkService() {
        return new KfeWalletNetworkService(directory, wallets, addresses, chainProvider, coreProvider,
                hash, audit, psbts, approval, derivation, validator, invoices, 200);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    /** Real Spring transaction/synchronization lifecycle; deliberately no database emulation. */
    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
