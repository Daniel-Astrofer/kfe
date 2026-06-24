package source.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.dto.KfeReserveOverviewResponse;
import source.kfe.repository.KfeBalanceRepository;

@Service
public class KfeReserveOverviewService {

    private final KfeBalanceRepository balanceRepository;

    public KfeReserveOverviewService(KfeBalanceRepository balanceRepository) {
        this.balanceRepository = balanceRepository;
    }

    @Transactional(readOnly = true)
    public KfeReserveOverviewResponse overview() {
        var balances = balanceRepository.findAll();
        long availableSats = 0;
        long pendingSats = 0;
        long lockedSats = 0;
        long holdSats = 0;
        long observedSats = 0;
        for (var balance : balances) {
            availableSats += balance.getAvailableSats();
            pendingSats += balance.getPendingSats();
            lockedSats += balance.getLockedSats();
            holdSats += balance.getAutoHoldSats();
            observedSats += balance.getObservedSats();
        }
        long reservedSats = lockedSats + holdSats;
        long totalSats = availableSats + pendingSats + reservedSats + observedSats;
        return new KfeReserveOverviewResponse(
                btc(totalSats), 0.0, 0.0, 0.0,
                btc(reservedSats), 0.0,
                btc(availableSats), 0.0,
                availableSats > 0,
                state(availableSats, reservedSats, observedSats));
    }

    private String state(long availableSats, long reservedSats, long observedSats) {
        if (availableSats <= 0 && reservedSats <= 0 && observedSats <= 0) return "EMPTY";
        if (availableSats <= 0) return "RESERVED";
        if (reservedSats > availableSats) return "TIGHT";
        return "HEALTHY";
    }

    private double btc(long sats) {
        return sats / 100_000_000.0;
    }
}
