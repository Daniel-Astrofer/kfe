package source.kfe.service;

import org.junit.jupiter.api.Test;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KfeResponseMapperTest {

    private final KfeResponseMapper mapper = new KfeResponseMapper(
            mock(KfeWalletAddressRepository.class),
            mock(KfeWalletRepository.class));

    @Test
    void mapsDurableExternalDetailsAndSenderPerspective() {
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(10L, sourceWalletId, destinationWalletId);

        var response = mapper.toTransactionResponse(tx, 10L);

        assertThat(response.walletId()).isEqualTo(sourceWalletId);
        assertThat(response.externalReference()).isEqualTo("bcrt1qdestination");
        assertThat(response.memo()).isEqualTo("invoice 42");
        // Taxonomy normalizes BITCOIN_CORE → CUSTODIAL_ONCHAIN for clients.
        assertThat(response.provider()).isEqualTo("CUSTODIAL_ONCHAIN");
        assertThat(response.providerReference()).isEqualTo("provider-reference");
        assertThat(response.paymentHash()).isEqualTo("payment-hash");
        assertThat(response.quorumProposalHash()).isNull();
        assertThat(response.quorumAckCount()).isZero();
    }

    @Test
    void mapsDestinationWalletForInternalReceiverPerspective() {
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(10L, sourceWalletId, destinationWalletId);

        var response = mapper.toTransactionResponse(tx, 20L);

        assertThat(response.walletId()).isEqualTo(destinationWalletId);
    }

    private KfeTransactionEntity transaction(Long userId, UUID sourceWalletId, UUID destinationWalletId) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(userId);
        tx.setIdempotencyKey("idempotency-key");
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setRail(KfeRail.INTERNAL);
        tx.setDirection(KfeDirection.INTERNAL);
        tx.setSourceWalletId(sourceWalletId);
        tx.setDestinationWalletId(destinationWalletId);
        tx.setExternalReference("bcrt1qdestination");
        tx.setMemo("invoice 42");
        tx.setProvider("BITCOIN_CORE");
        tx.setProviderReference("provider-reference");
        tx.setPaymentHash("payment-hash");
        return tx;
    }
}
