package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import source.common.service.AddressDerivationService;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory index of on-chain addresses belonging to active WATCH_ONLY wallets.
 * Used by the ZMQ reactive path to filter mempool/block activity without scanning
 * every wallet on every rawtx.
 */
@Component
public class KfeMonitoredChainAddressIndex {

    private static final Logger log = LoggerFactory.getLogger(KfeMonitoredChainAddressIndex.class);

    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final AddressDerivationService addressDerivationService;
    private final KfeTransactionRepository transactionRepository;
    private final int externalGap;
    private final int changeGap;
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(Snapshot.empty());

    public KfeMonitoredChainAddressIndex(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            AddressDerivationService addressDerivationService,
            KfeTransactionRepository transactionRepository,
            @Value("${kfe.bitcoin.zmq.address-gap-external:${kfe.descriptor-scan-range:200}}")
                    int externalGap,
            @Value("${kfe.bitcoin.zmq.address-gap-change:${kfe.descriptor-scan-range:200}}")
                    int changeGap) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.addressDerivationService = addressDerivationService;
        this.transactionRepository = transactionRepository;
        this.externalGap = Math.max(1, externalGap);
        this.changeGap = Math.max(0, changeGap);
    }

    @Scheduled(
            fixedDelayString = "${kfe.bitcoin.zmq.address-cache-refresh-ms:30000}",
            initialDelayString = "8000")
    public void refresh() {
        try {
            rebuild();
        } catch (RuntimeException exception) {
            log.warn("[KFE ZMQ] address index refresh failed: {}", exception.getMessage());
        }
    }

    public void rebuild() {
        // Cold + custodial + INTERNAL receive addresses — ZMQ must see platform sinks too.
        List<KfeWalletEntity> monitoredWallets = walletRepository.findByKindInAndStatus(
                List.of(
                        KfeWalletKind.WATCH_ONLY,
                        KfeWalletKind.CUSTODIAL_ONCHAIN,
                        KfeWalletKind.INTERNAL),
                KfeWalletStatus.ACTIVE);
        Map<String, UUID> addressToWallet = new HashMap<>();
        Map<String, UUID> fundingTxidToWallet = new HashMap<>();
        Set<UUID> walletIds = new HashSet<>();
        for (KfeWalletEntity wallet : monitoredWallets) {
            walletIds.add(wallet.getId());
            List<KfeWalletAddressEntity> addresses =
                    addressRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
            for (KfeWalletAddressEntity row : addresses) {
                if (row.getStatus() != null && row.getStatus() != KfeWalletAddressStatus.ACTIVE) {
                    continue;
                }
                String address = normalize(row.getAddress());
                if (address == null) {
                    continue;
                }
                addressToWallet.putIfAbsent(address, wallet.getId());
            }
            if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
                expandFromXpub(wallet, addressToWallet);
                // Known cold inbound funding txids — so ZMQ can detect Electrum spends of those
                // UTXOs even when no change returns to a monitored address.
                try {
                    List<KfeTransactionEntity> inbounds =
                            transactionRepository.findByDestinationWalletIdAndProvider(
                                    wallet.getId(),
                                    KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER);
                    for (KfeTransactionEntity inbound : inbounds) {
                        String funding = inbound.getBlockchainTxid();
                        if (funding == null || funding.isBlank()) {
                            continue;
                        }
                        fundingTxidToWallet.putIfAbsent(
                                funding.trim().toLowerCase(Locale.ROOT), wallet.getId());
                    }
                } catch (RuntimeException exception) {
                    log.debug(
                            "[KFE ZMQ] funding txid index failed walletId={}: {}",
                            wallet.getId(),
                            exception.getMessage());
                }
            }
        }
        snapshot.set(new Snapshot(
                Collections.unmodifiableMap(addressToWallet),
                Collections.unmodifiableMap(fundingTxidToWallet),
                Collections.unmodifiableSet(walletIds)));
        log.debug(
                "[KFE ZMQ] address index rebuilt wallets={} addresses={} fundingTxids={}",
                walletIds.size(),
                addressToWallet.size(),
                fundingTxidToWallet.size());
    }

    public Set<UUID> allColdWalletIds() {
        return snapshot.get().walletIds();
    }

    public UUID walletIdForAddress(String address) {
        String key = normalize(address);
        if (key == null) {
            return null;
        }
        return snapshot.get().addressToWallet().get(key);
    }

    public Set<UUID> walletIdsForAddresses(Iterable<String> addresses) {
        Set<UUID> hits = new HashSet<>();
        for (String address : addresses) {
            UUID walletId = walletIdForAddress(address);
            if (walletId != null) {
                hits.add(walletId);
            }
        }
        return hits;
    }

    public UUID walletIdForFundingTxid(String fundingTxid) {
        if (fundingTxid == null || fundingTxid.isBlank()) {
            return null;
        }
        Map<String, UUID> map = snapshot.get().fundingTxidToWallet();
        if (map == null || map.isEmpty()) {
            return null;
        }
        return map.get(fundingTxid.trim().toLowerCase(Locale.ROOT));
    }

    public Set<UUID> walletIdsForFundingTxids(Iterable<String> fundingTxids) {
        Set<UUID> hits = new HashSet<>();
        if (fundingTxids == null) {
            return hits;
        }
        for (String txid : fundingTxids) {
            UUID walletId = walletIdForFundingTxid(txid);
            if (walletId != null) {
                hits.add(walletId);
            }
        }
        return hits;
    }

    public int addressCount() {
        return snapshot.get().addressToWallet().size();
    }

    private void expandFromXpub(KfeWalletEntity wallet, Map<String, UUID> addressToWallet) {
        String xpub = wallet.getXpub();
        if (xpub == null || xpub.isBlank()) {
            return;
        }
        try {
            String networkXpub = addressDerivationService.toNetworkExtendedPublicKey(xpub.trim());
            for (int i = 0; i < externalGap; i++) {
                putDerived(addressToWallet, wallet.getId(), networkXpub, i, false);
            }
            for (int i = 0; i < changeGap; i++) {
                putDerived(addressToWallet, wallet.getId(), networkXpub, i, true);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE ZMQ] xpub gap expand failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    private void putDerived(
            Map<String, UUID> addressToWallet,
            UUID walletId,
            String networkXpub,
            int index,
            boolean change) {
        try {
            String address = addressDerivationService.deriveAddressFromXpub(networkXpub, index, change);
            String key = normalize(address);
            if (key != null) {
                addressToWallet.putIfAbsent(key, walletId);
            }
        } catch (RuntimeException ignored) {
            // skip bad index
        }
    }

    private static String normalize(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private record Snapshot(
            Map<String, UUID> addressToWallet,
            Map<String, UUID> fundingTxidToWallet,
            Set<UUID> walletIds) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Set.of());
        }
    }
}
