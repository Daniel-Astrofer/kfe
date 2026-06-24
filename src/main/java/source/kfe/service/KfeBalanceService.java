package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import source.kfe.model.KfeBalanceEntity;
import source.kfe.model.KfeBalanceId;
import source.kfe.repository.KfeBalanceRepository;
import source.kfe.repository.KfeWalletRepository;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class KfeBalanceService {

    private static final Logger log = LoggerFactory.getLogger(KfeBalanceService.class);

    private final KfeBalanceRepository balanceRepository;
    private final KfeHashService hashService;
    private final KfeWalletRepository walletRepository;
    private final BalanceEventPublisher balanceEventPublisher;

    public KfeBalanceService(KfeBalanceRepository balanceRepository,
                             KfeHashService hashService,
                             KfeWalletRepository walletRepository,
                             BalanceEventPublisher balanceEventPublisher) {
        this.balanceRepository = balanceRepository;
        this.hashService = hashService;
        this.walletRepository = walletRepository;
        this.balanceEventPublisher = balanceEventPublisher;
    }

    public KfeBalanceEntity createEmptyBalance(UUID walletId, String asset) {
        String normalizedAsset = asset != null ? asset : "BTC";
        String initialHash = hashService.initialBalanceHash(walletId.toString(), normalizedAsset);
        KfeBalanceEntity balance = KfeBalanceEntity.empty(walletId, normalizedAsset, initialHash);
        balance.setBalanceSignature(hashService.balanceHash(balance));
        return balanceRepository.save(balance);
    }

    public KfeBalanceEntity requireForUpdate(UUID walletId, String asset) {
        return balanceRepository.findByWalletIdAndAssetForUpdate(walletId, asset != null ? asset : "BTC")
                .orElseThrow(() -> new IllegalArgumentException("KFE balance not found for wallet " + walletId + "."));
    }

    public KfeBalanceEntity reserve(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.reserve(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceUpdate(walletId, saved.getAvailableSats(), -amountSats, "reserva");
        return saved;
    }

    public KfeBalanceEntity settleReservedDebit(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.settleReservedDebit(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceUpdate(walletId, saved.getAvailableSats(), 0L, "liquidação de débito");
        return saved;
    }

    public KfeBalanceEntity releaseReserved(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.releaseReserved(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceUpdate(walletId, saved.getAvailableSats(), amountSats, "liberação de reserva");
        return saved;
    }

    public KfeBalanceEntity creditAvailable(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.creditAvailable(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceUpdate(walletId, saved.getAvailableSats(), amountSats, "crédito");
        return saved;
    }

    public KfeBalanceEntity setObserved(UUID walletId, String asset, long observedSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        long oldObserved = balance.getObservedSats();
        balance.setObservedBalance(observedSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceUpdate(walletId, saved.getObservedSats(), observedSats - oldObserved, "observado");
        return saved;
    }

    private void sign(KfeBalanceEntity balance) {
        String hash = hashService.balanceHash(balance);
        balance.setLastHash(hash);
        balance.setBalanceSignature(hash);
    }

    private void publishBalanceUpdate(UUID walletId, long newBalanceSats, long deltaSats, String context) {
        try {
            walletRepository.findById(walletId).ifPresent(wallet -> {
                BigDecimal newBalance = BigDecimal.valueOf(newBalanceSats).movePointLeft(8);
                BigDecimal amount = BigDecimal.valueOf(deltaSats).movePointLeft(8);
                balanceEventPublisher.publishBalanceUpdateAfterCommit(
                        wallet.getUserId(),
                        wallet.getId().toString(),
                        wallet.getLabel(),
                        newBalance,
                        amount,
                        context);
            });
        } catch (Exception e) {
            log.error("Failed to publish balance update for walletId={}", walletId, e);
        }
    }
}
