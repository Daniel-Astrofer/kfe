package com.kerosene.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.model.KfeLightningLiquidityReservationEntity;
import com.kerosene.kfe.model.KfeLiquidityReservationStatus;
import com.kerosene.kfe.rail.LightningClient;
import com.kerosene.kfe.rail.LightningPaymentGateway;
import com.kerosene.kfe.repository.KfeLightningLiquidityReservationRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeLightningLiquidityServiceTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<LightningClient> clientProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LightningPaymentGateway> paymentProvider = mock(ObjectProvider.class);
    private final KfeLightningLiquidityReservationRepository repository =
            mock(KfeLightningLiquidityReservationRepository.class);
    private final LightningClient client = mock(LightningClient.class);
    private final LightningPaymentGateway paymentGateway = mock(LightningPaymentGateway.class);

    private KfeLightningLiquidityService service;

    @BeforeEach
    void setUp() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(paymentProvider.getIfAvailable()).thenReturn(paymentGateway);
        when(paymentGateway.isLive()).thenReturn(true);
        when(client.getLocalBalance()).thenReturn(1_000_000L);
        when(repository.sumAmountByStatus(KfeLiquidityReservationStatus.HELD)).thenReturn(200_000L);
        when(repository.findByTransactionId(any())).thenReturn(Optional.empty());
        service = new KfeLightningLiquidityService(
                clientProvider, paymentProvider, repository, 0L, 0L);
    }

    @Test
    void freeCapacitySubtractsHeldReservations() {
        assertThat(service.freeOutboundCapacitySats()).isEqualTo(800_000L);
        assertThat(service.canCoverOutbound(800_000L)).isTrue();
        assertThat(service.canCoverOutbound(800_001L)).isFalse();
    }

    @Test
    void reserveLocksPoolAndPersistsHeldRow() {
        UUID txId = UUID.randomUUID();
        service.reserveForTransaction(txId, 100_000L);

        verify(repository).acquirePoolLock(KfeLightningLiquidityService.POOL_LOCK_KEY);
        verify(repository).saveAndFlush(any(KfeLightningLiquidityReservationEntity.class));
    }

    @Test
    void reserveFailsWhenFreeCapacityInsufficient() {
        UUID txId = UUID.randomUUID();
        assertThatThrownBy(() -> service.reserveForTransaction(txId, 900_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient Lightning outbound liquidity");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void releaseAndConsumeAreTerminal() {
        UUID txId = UUID.randomUUID();
        KfeLightningLiquidityReservationEntity held = new KfeLightningLiquidityReservationEntity();
        held.setTransactionId(txId);
        held.setAmountSats(50_000L);
        held.setStatus(KfeLiquidityReservationStatus.HELD);
        when(repository.findByTransactionId(txId)).thenReturn(Optional.of(held));

        service.consumeForTransaction(txId);
        assertThat(held.getStatus()).isEqualTo(KfeLiquidityReservationStatus.CONSUMED);
        verify(repository).save(held);

        held.setStatus(KfeLiquidityReservationStatus.HELD);
        service.releaseForTransaction(txId);
        assertThat(held.getStatus()).isEqualTo(KfeLiquidityReservationStatus.RELEASED);
    }
}
