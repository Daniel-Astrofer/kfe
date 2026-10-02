package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.FinancialMpcKeyPort;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.repository.KfeUserStatementRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeAddressKeyStatementMaintenanceTest {
    private enum Root { XPUB, RPC_ADDRESS, KEYGEN, SYSTEM_WALLETS, STATEMENT, BEST_EFFORT, IF_ABSENT, REFRESH }
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final AddressDerivationService derivation = mock(AddressDerivationService.class);
    private final KfeDerivationCursorService cursors = mock(KfeDerivationCursorService.class);
    private final BitcoinCoreRpcClient rpc = mock(BitcoinCoreRpcClient.class);
    private final FinancialMpcKeyPort keys = mock(FinancialMpcKeyPort.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeHashService hashes = mock(KfeHashService.class);
    private final KfeUserStatementRepository rows = mock(KfeUserStatementRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final TransactionEventPublisher publisher = mock(TransactionEventPublisher.class);
    private final KfeStatementService self = mock(KfeStatementService.class);
    private final KfeTransactionEntity transaction = new KfeTransactionEntity();
    private KfeReceiveAddressIssuer xpub, address;
    private KfeMpcKeyService mpc;
    private KfeSystemWalletService system;
    private KfeStatementService statements;

    @BeforeEach void setup() {
        xpub = new KfeReceiveAddressIssuer(derivation, cursors, provider(rpc), "synthetic-xpub", false);
        address = new KfeReceiveAddressIssuer(derivation, cursors, provider(rpc), "", true);
        mpc = new KfeMpcKeyService(keys);
        system = new KfeSystemWalletService(wallets, balances, hashes, 0L, "Funds", "Profit", "SUBLEDGER");
        statements = new KfeStatementService(rows, new ObjectMapper(), em, provider(publisher), self);
        transaction.setUserId(7L);
        inject(guard);
        when(store.admit(anyString())).thenReturn(admission);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void drainRejectsBeforeCursorRpcKeygenFlushOrFinancialWrites(Root root) {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain"));
        rejected(root); noEffects(); verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void missingInjectionIsFailClosed(Root root) {
        inject(KfeMaintenanceGuard.unavailable()); rejected(root); noEffects(); verifyNoInteractions(store);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void storageOutageCannotStartEffects(Root root) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic outage"));
        rejected(root); noEffects();
    }

    @Test void successfulRemoteReplyDoesNotProveKeyOrAddressRecovery() {
        when(keys.keygenWallet(any(), eq(7L))).thenReturn("synthetic-key-reference");
        assertThat(mpc.keygenWallet(transaction.getId(), 7L)).isEqualTo("synthetic-key-reference");
        verify(store).resolve(admission.id(), false);
        verifyNoInteractions(rpc, wallets, balances, rows, em);
    }

    @Test void swallowedBestEffortFailureStillLeavesUncertainty() {
        doThrow(new IllegalStateException("synthetic statement failure")).when(self)
                .recordUserStatement(7L, null, transaction, Map.of("status", "synthetic"));
        statements.recordUserStatementBestEffort(7L, null, transaction, Map.of("status", "synthetic"));
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @Test void pureCapabilitiesAndConfigurationStayReadableWithoutAdmission() {
        inject(KfeMaintenanceGuard.unavailable());
        assertThat(xpub.canIssue()).isTrue(); assertThat(address.canIssue()).isTrue();
        assertThat(system.systemUserId()).isZero(); assertThat(system.isProfitSubledger()).isTrue();
        assertThat(system.profitSegregationMode()).isEqualTo("SUBLEDGER");
        statements.recordUserStatement(null, null, null, null);
        statements.recordUserStatementBestEffort(null, null, null, null);
        statements.recordUserStatementIfAbsent(null, null, null, null);
        statements.refreshTransactionDisplayPayload(null, null);
        verifyNoInteractions(store); noEffects();
    }

    @Test void profitLookupNeverCreatesAccountingWalletsDuringDrain() {
        inject(KfeMaintenanceGuard.unavailable());
        when(wallets.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(any(), any(), anyCollection()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(system::requireProfitWalletId).isInstanceOf(IllegalStateException.class);
        verify(wallets, never()).save(any()); verifyNoInteractions(store, balances, hashes, keys, rpc);
    }

    private void inject(KfeMaintenanceGuard value) {
        xpub.setMaintenanceGuard(value); address.setMaintenanceGuard(value); mpc.setMaintenanceGuard(value);
        system.setMaintenanceGuard(value); statements.setMaintenanceGuard(value);
    }
    private void rejected(Root root) { assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class); }
    private void noEffects() { verifyNoInteractions(derivation, cursors, rpc, keys, wallets, balances, hashes, rows, em, publisher, self); }
    private void invoke(Root root) {
        switch (root) {
            case XPUB -> xpub.issue("synthetic");
            case RPC_ADDRESS -> address.issue("synthetic");
            case KEYGEN -> mpc.keygenWallet(transaction.getId(), 7L);
            case SYSTEM_WALLETS -> system.ensureSystemWallets();
            case STATEMENT -> statements.recordUserStatement(7L, null, transaction, Map.of("status", "synthetic"));
            case BEST_EFFORT -> statements.recordUserStatementBestEffort(7L, null, transaction, Map.of("status", "synthetic"));
            case IF_ABSENT -> statements.recordUserStatementIfAbsent(7L, null, transaction, Map.of("status", "synthetic"));
            case REFRESH -> statements.refreshTransactionDisplayPayload(transaction, Map.of("status", "synthetic"));
        }
    }
    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value); return provider;
    }
}
