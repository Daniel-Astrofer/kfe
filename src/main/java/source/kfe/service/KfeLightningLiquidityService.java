package source.kfe.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.model.KfeLightningLiquidityReservationEntity;
import source.kfe.model.KfeLiquidityReservationStatus;
import source.kfe.rail.LightningClient;
import source.kfe.rail.LightningPaymentGateway;
import source.kfe.repository.KfeLightningLiquidityReservationRepository;

import java.util.UUID;

/**
 * Outbound Lightning liquidity: capacity probe + reservation until HTLC terminal state.
 * Pooled node model — free capacity = local balance − sum(HELD reservations).
 */
@Service
public class KfeLightningLiquidityService {

    /** Stable advisory lock key for the platform LN outbound pool. */
    static final long POOL_LOCK_KEY = 0x4B46454C4E4C5154L; // "KFELNLIQ" truncated

    private final ObjectProvider<LightningClient> lightningClientProvider;
    private final ObjectProvider<LightningPaymentGateway> paymentGatewayProvider;
    private final KfeLightningLiquidityReservationRepository reservationRepository;
    private final long minOutboundSats;
    private final long circuitBreakerFloorSats;

    public KfeLightningLiquidityService(
            ObjectProvider<LightningClient> lightningClientProvider,
            @org.springframework.beans.factory.annotation.Qualifier("kfeExternalLightningPaymentGateway")
            ObjectProvider<LightningPaymentGateway> paymentGatewayProvider,
            KfeLightningLiquidityReservationRepository reservationRepository,
            @Value("${kfe.lightning.min-outbound-sats:0}") long minOutboundSats,
            @Value("${kfe.lightning.circuit-breaker-floor-sats:0}") long circuitBreakerFloorSats) {
        this.lightningClientProvider = lightningClientProvider;
        this.paymentGatewayProvider = paymentGatewayProvider;
        this.reservationRepository = reservationRepository;
        this.minOutboundSats = Math.max(0L, minOutboundSats);
        this.circuitBreakerFloorSats = Math.max(0L, circuitBreakerFloorSats);
    }

    public boolean isLive() {
        LightningPaymentGateway gateway = paymentGatewayProvider.getIfAvailable();
        if (gateway != null && gateway.isLive()) {
            return true;
        }
        LightningClient client = lightningClientProvider.getIfAvailable();
        return client != null;
    }

    /** Local channel balance in sats, or -1 if not probeable. */
    public long outboundCapacitySats() {
        LightningClient client = lightningClientProvider.getIfAvailable();
        if (client == null) {
            return -1L;
        }
        try {
            return Math.max(0L, client.getLocalBalance());
        } catch (RuntimeException ex) {
            return -1L;
        }
    }

    public long heldReservationSats() {
        return Math.max(0L, reservationRepository.sumAmountByStatus(KfeLiquidityReservationStatus.HELD));
    }

    /** Free outbound capacity after existing HELD reservations. */
    public long freeOutboundCapacitySats() {
        long capacity = outboundCapacitySats();
        if (capacity < 0L) {
            return -1L;
        }
        return Math.max(0L, capacity - heldReservationSats());
    }

    public boolean canCoverOutbound(long totalDebitSats) {
        long free = freeOutboundCapacitySats();
        if (free < 0L) {
            return false;
        }
        if (totalDebitSats <= 0L) {
            return false;
        }
        if (minOutboundSats > 0L && free < minOutboundSats) {
            return false;
        }
        return free >= totalDebitSats;
    }

    public boolean circuitBreakerOpen() {
        if (circuitBreakerFloorSats <= 0L) {
            return false;
        }
        long capacity = outboundCapacitySats();
        if (capacity < 0L) {
            return true;
        }
        return capacity < circuitBreakerFloorSats;
    }

    /**
     * Atomically reserve outbound capacity until HTLC resolves.
     * Idempotent per transactionId.
     */
    @Transactional
    public void reserveForTransaction(UUID transactionId, long amountSats) {
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId is required for liquidity reservation.");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("liquidity reservation amount must be positive.");
        }
        if (reservationRepository.findByTransactionId(transactionId).isPresent()) {
            return;
        }
        reservationRepository.acquirePoolLock(POOL_LOCK_KEY);
        if (!canCoverOutbound(amountSats)) {
            throw new IllegalStateException(
                    "Insufficient Lightning outbound liquidity for reservation of " + amountSats + " sats.");
        }
        KfeLightningLiquidityReservationEntity reservation = new KfeLightningLiquidityReservationEntity();
        reservation.setTransactionId(transactionId);
        reservation.setAmountSats(amountSats);
        reservation.setStatus(KfeLiquidityReservationStatus.HELD);
        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent insert for same tx — treat as idempotent success.
            if (reservationRepository.findByTransactionId(transactionId).isEmpty()) {
                throw ex;
            }
        }
    }

    @Transactional
    public void consumeForTransaction(UUID transactionId) {
        finalize(transactionId, true);
    }

    @Transactional
    public void releaseForTransaction(UUID transactionId) {
        finalize(transactionId, false);
    }

    private void finalize(UUID transactionId, boolean consumed) {
        if (transactionId == null) {
            return;
        }
        reservationRepository.findByTransactionId(transactionId).ifPresent(reservation -> {
            if (reservation.getStatus() != KfeLiquidityReservationStatus.HELD) {
                return;
            }
            if (consumed) {
                reservation.markConsumed();
            } else {
                reservation.markReleased();
            }
            reservationRepository.save(reservation);
        });
    }
}
