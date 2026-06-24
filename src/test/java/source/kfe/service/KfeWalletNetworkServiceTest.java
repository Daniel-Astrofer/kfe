package source.kfe.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import source.common.financial.FinancialTransactionApprovalPort;
import source.common.financial.FinancialUserDirectoryPort;
import source.kfe.dto.KfeColdWalletPsbtRequest;
import source.kfe.dto.KfeColdWalletPsbtResponse;
import source.kfe.dto.KfeReceivingCapabilitiesResponse;
import source.kfe.dto.KfeUtxoResponse;
import source.kfe.model.KfePsbtWorkflowEntity;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.rail.BitcoinCoreRpcClient;
import source.kfe.rail.BlockchainClient;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeWalletNetworkServiceTest {

    private final FinancialUserDirectoryPort userDirectory = mock(FinancialUserDirectoryPort.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final ObjectProvider<BlockchainClient> blockchainClientProvider = mock(ObjectProvider.class);
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider = mock(ObjectProvider.class);
    private final KfeHashService hashService = new KfeHashService();
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfePsbtWorkflowService psbtWorkflowService = mock(KfePsbtWorkflowService.class);
    private final FinancialTransactionApprovalPort transactionApprovalPort = mock(FinancialTransactionApprovalPort.class);
    private final KfeWalletNetworkService service = new KfeWalletNetworkService(
            userDirectory,
            walletRepository,
            addressRepository,
            blockchainClientProvider,
            bitcoinCoreProvider,
            hashService,
            auditLogService,
            psbtWorkflowService,
            transactionApprovalPort);

    @Test
    void returnsKfeReceivingCapabilitiesFromActiveWallets() {
        FinancialUserDirectoryPort.FinancialUserHandle user =
                new FinancialUserDirectoryPort.FinancialUserHandle(42L, "alice", true);

        KfeWalletEntity wallet = wallet(KfeWalletKind.INTERNAL);
        KfeWalletAddressEntity address = address(wallet.getId());

        when(userDirectory.findByUsername("alice")).thenReturn(Optional.of(user));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(wallet));
        when(addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(Optional.of(address));

        KfeReceivingCapabilitiesResponse response = service.receivingCapabilities("@alice");

        assertThat(response.canReceiveInternal()).isTrue();
        assertThat(response.canReceiveOnchain()).isTrue();
        assertThat(response.canReceiveLightning()).isFalse();
        assertThat(response.receiverDisplayName()).isEqualTo("@alice");
        assertThat(response.internalWalletId()).isEqualTo(wallet.getId());
        assertThat(response.availableRails()).containsExactly("INTERNAL", "ONCHAIN");
        assertThat(response.missingRequirements()).containsExactly("KFE_LIGHTNING_RECEIVE_NOT_CONFIGURED");
    }

    @Test
    void listsUtxosForActiveKfeWalletAddresses() {
        KfeWalletEntity wallet = wallet(KfeWalletKind.WATCH_ONLY);
        KfeWalletAddressEntity address = address(wallet.getId());
        BlockchainClient blockchainClient = mock(BlockchainClient.class);

        when(walletRepository.findByIdAndUserId(wallet.getId(), 42L)).thenReturn(Optional.of(wallet));
        when(blockchainClientProvider.getIfAvailable()).thenReturn(blockchainClient);
        when(addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(),
                KfeWalletAddressStatus.ACTIVE)).thenReturn(List.of(address));
        when(blockchainClient.getUnspentOutputs(address.getAddress())).thenReturn(List.of(
                new BlockchainClient.AddressUtxo("txid-1", 0, 1000L, "0014abcd")));

        List<KfeUtxoResponse> response = service.listUtxos(42L, wallet.getId());

        assertThat(response).containsExactly(new KfeUtxoResponse(
                "txid-1",
                0,
                1000L,
                "0014abcd",
                address.getAddress()));
    }

    @Test
    void createsColdWalletPsbtOnlyForWatchOnlyWallets() {
        KfeWalletEntity wallet = wallet(KfeWalletKind.WATCH_ONLY);
        BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        KfeColdWalletPsbtRequest request = new KfeColdWalletPsbtRequest(
                "bc1qdestxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                10_000L,
                6,
                null,
                List.of(new KfeColdWalletPsbtRequest.Input("txid-1", 0)),
                "totp-code");

        when(walletRepository.findByIdAndUserId(wallet.getId(), 42L)).thenReturn(Optional.of(wallet));
        when(bitcoinCoreProvider.getIfAvailable()).thenReturn(bitcoinCore);
        when(bitcoinCore.createWatchOnlyPsbt(
                anyList(),
                eq(request.destinationAddress()),
                eq(request.amountSats()),
                eq(request.confirmationTarget()),
                isNull())).thenReturn(new BitcoinCoreRpcClient.FundedPsbt("psbt-value", 250L));
        KfePsbtWorkflowEntity workflow = new KfePsbtWorkflowEntity();
        when(psbtWorkflowService.create(
                eq(42L),
                eq(wallet.getId()),
                eq("psbt-value"),
                eq(hashService.sha256("psbt-value")),
                eq(250L),
                eq(request.amountSats()),
                eq(request.destinationAddress()),
                eq(request.inputs()))).thenReturn(workflow);

        KfeColdWalletPsbtResponse response = service.createColdWalletPsbt(42L, wallet.getId(), request);

        assertThat(response.workflowId()).isEqualTo(workflow.getId());
        assertThat(response.psbt()).isEqualTo("psbt-value");
        assertThat(response.psbtHash()).isEqualTo(hashService.sha256("psbt-value"));
        assertThat(response.feeSats()).isEqualTo(250L);
        verify(transactionApprovalPort).approveColdWalletPsbt(42L, request.totpCode());
        verify(auditLogService).record(
                eq("KFE_COLD_WALLET_PSBT_CREATED"),
                isNull(),
                eq(wallet.getId()),
                isNull(),
                isNull(),
                any());
    }

    private KfeWalletEntity wallet(KfeWalletKind kind) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(42L);
        wallet.setKind(kind);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setSpendable(kind != KfeWalletKind.WATCH_ONLY);
        wallet.setLabel("Treasury");
        return wallet;
    }

    private KfeWalletAddressEntity address(UUID walletId) {
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(walletId);
        address.setAddress("bc1qsourcexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        address.setStatus(KfeWalletAddressStatus.ACTIVE);
        return address;
    }
}
