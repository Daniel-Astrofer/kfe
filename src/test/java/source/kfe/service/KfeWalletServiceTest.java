package source.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import source.common.financial.DevBalanceInjector;
import source.common.service.AddressDerivationService;
import source.kfe.dto.KfeCreateWalletRequest;
import source.kfe.dto.KfeUpdateWalletRequest;
import source.kfe.dto.KfeWalletResponse;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KfeWalletServiceTest {

    @Mock
    private KfeWalletRepository walletRepository;

    @Mock
    private KfeWalletAddressRepository addressRepository;

    @Mock
    private KfeBalanceService balanceService;

    @Mock
    private KfeHashService hashService;

    @Mock
    private KfeAuditLogService auditLogService;

    @Mock
    private KfeQuorumGateway quorumGateway;

    @Mock
    private KfeMpcKeyService mpcKeyService;

    @Mock
    private KfeResponseMapper responseMapper;

    @Mock
    private KfeDashboardPublisher dashboardPublisher;

    @Mock
    private AddressDerivationService addressDerivationService;

    @Mock
    private KfeReceiveAddressIssuer receiveAddressIssuer;

    @Mock
    private DevBalanceInjector devBalanceInjector;

    @Mock
    private TransactionTemplate transactionTemplate;

    private KfeWalletService service;

    @BeforeEach
    void setUp() {
        service = new KfeWalletService(
                walletRepository,
                addressRepository,
                balanceService,
                hashService,
                auditLogService,
                quorumGateway,
                mpcKeyService,
                responseMapper,
                dashboardPublisher,
                addressDerivationService,
                receiveAddressIssuer,
                devBalanceInjector,
                transactionTemplate
        );
    }

    @Test
    void listWalletsReturnsMappedResponses() {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(1L);

        when(walletRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(List.of(wallet));
        when(responseMapper.toWalletResponse(wallet)).thenReturn(new KfeWalletResponse(
                wallet.getId(), KfeWalletKind.CUSTODIAL_ONCHAIN, KfeWalletStatus.ACTIVE, "label", "label",
                "Carteira Onchain", "BTC",
                true, true, true, "xpub", java.time.LocalDateTime.now(), java.time.LocalDateTime.now()
        ));

        List<KfeWalletResponse> responses = service.listWallets(1L);

        assertEquals(1, responses.size());
        verify(walletRepository).findByUserIdAndStatusInOrderByCreatedAtDesc(eq(1L), any());
        verify(responseMapper).toWalletResponse(wallet);
    }

    @Test
    void createWalletAcceptsCustomLabelAndCustodialOnchainWithoutXpub() {
        AtomicReference<KfeWalletEntity> persistedWallet = new AtomicReference<>();

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(walletRepository.save(any(KfeWalletEntity.class))).thenAnswer(invocation -> {
            KfeWalletEntity wallet = invocation.getArgument(0);
            persistedWallet.set(wallet);
            return wallet;
        });
        when(hashService.sha256(anyString())).thenReturn("proposal-hash");
        when(quorumGateway.requireHealthyUnanimousConsensus(anyString()))
                .thenReturn(new KfeQuorumGateway.Result(2, 2));
        when(mpcKeyService.keygenWallet(any(UUID.class), eq(1L)))
                .thenReturn("mpc-public-key");
        when(walletRepository.findByIdAndUserIdForUpdate(any(UUID.class), eq(1L)))
                .thenAnswer(invocation -> Optional.ofNullable(persistedWallet.get()));
        when(responseMapper.toWalletResponse(any(KfeWalletEntity.class)))
                .thenAnswer(invocation -> {
                    KfeWalletEntity wallet = invocation.getArgument(0);
                    return new KfeWalletResponse(
                            wallet.getId(),
                            wallet.getKind(),
                            wallet.getStatus(),
                            wallet.getLabel(),
                            wallet.getLabel(),
                            "Carteira Onchain",
                            "BTC",
                            wallet.isSpendable(),
                            false,
                            true,
                            null,
                            java.time.LocalDateTime.now(),
                            java.time.LocalDateTime.now());
                });

        KfeWalletResponse response = service.createWallet(
                1L,
                new KfeCreateWalletRequest(
                        KfeWalletKind.CUSTODIAL_ONCHAIN,
                        null,
                        "Reserva familiar",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false));

        assertEquals("Reserva familiar", response.label());
        assertEquals(KfeWalletKind.CUSTODIAL_ONCHAIN, response.kind());
        assertEquals("Reserva familiar", persistedWallet.get().getLabel());
        assertEquals("mpc-public-key", persistedWallet.get().getMpcPublicKey());
    }

    @Test
    void createWalletForcesInternalLabelToAssuredAccount() {
        AtomicReference<KfeWalletEntity> persistedWallet = new AtomicReference<>();

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(walletRepository.save(any(KfeWalletEntity.class))).thenAnswer(invocation -> {
            KfeWalletEntity wallet = invocation.getArgument(0);
            persistedWallet.set(wallet);
            return wallet;
        });
        when(hashService.sha256(anyString())).thenReturn("proposal-hash");
        when(quorumGateway.requireHealthyUnanimousConsensus(anyString()))
                .thenReturn(new KfeQuorumGateway.Result(2, 2));
        when(walletRepository.findByIdAndUserIdForUpdate(any(UUID.class), eq(1L)))
                .thenAnswer(invocation -> Optional.ofNullable(persistedWallet.get()));
        when(responseMapper.toWalletResponse(any(KfeWalletEntity.class)))
                .thenAnswer(invocation -> {
                    KfeWalletEntity wallet = invocation.getArgument(0);
                    return new KfeWalletResponse(
                            wallet.getId(),
                            wallet.getKind(),
                            wallet.getStatus(),
                            wallet.getLabel(),
                            wallet.getLabel(),
                            "Conta Assegurada",
                            "BTC",
                            wallet.isSpendable(),
                            false,
                            true,
                            null,
                            java.time.LocalDateTime.now(),
                            java.time.LocalDateTime.now());
                });

        KfeWalletResponse response = service.createWallet(
                1L,
                new KfeCreateWalletRequest(
                        KfeWalletKind.INTERNAL,
                        null,
                        "Outro nome",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false));

        assertEquals("Conta Assegurada", response.label());
        assertEquals("Conta Assegurada", persistedWallet.get().getLabel());
        verifyNoInteractions(mpcKeyService);
    }

    @Test
    void createWalletRejectsSecondActiveWalletForSameKind() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(walletRepository.countByUserIdAndKindAndStatusIn(
                eq(1L),
                eq(KfeWalletKind.CUSTODIAL_ONCHAIN),
                any()
        )).thenReturn(1L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.createWallet(
                        1L,
                        new KfeCreateWalletRequest(
                                KfeWalletKind.CUSTODIAL_ONCHAIN,
                                null,
                                "Segunda custodial",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false)));

        assertEquals("Já existe uma carteira ativa ou em criação para este método de custódia.", exception.getMessage());
        verify(walletRepository, never()).save(any(KfeWalletEntity.class));
        verifyNoInteractions(quorumGateway, mpcKeyService);
    }

    @Test
    void createWalletRejectsThirdActiveWatchOnlyWallet() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(walletRepository.countByUserIdAndKindAndStatusIn(
                eq(1L),
                eq(KfeWalletKind.WATCH_ONLY),
                any()
        )).thenReturn(2L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.createWallet(
                        1L,
                        new KfeCreateWalletRequest(
                                KfeWalletKind.WATCH_ONLY,
                                null,
                                "Cold wallet 3",
                                "xpub6CUGRU...",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false)));

        assertEquals("É permitido criar no máximo duas carteiras frias ativas.", exception.getMessage());
        verify(walletRepository, never()).save(any(KfeWalletEntity.class));
        verifyNoInteractions(quorumGateway, mpcKeyService);
    }

    @Test
    void archivedWalletRejectsUpdateWithoutPersistingSensitiveChange() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setUserId(1L);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ARCHIVED);
        wallet.setLabel("Archived");

        when(walletRepository.findByIdAndUserIdForUpdate(walletId, 1L)).thenReturn(Optional.of(wallet));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.updateWallet(1L, walletId, new KfeUpdateWalletRequest("Sensitive rename")));

        assertEquals("Archived wallets cannot be updated.", exception.getMessage());
        assertEquals("Archived", wallet.getLabel());
        verify(walletRepository, never()).save(any(KfeWalletEntity.class));
        verifyNoInteractions(auditLogService, dashboardPublisher, responseMapper);
    }

}
