package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.rail.BlockchainClient;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Keeps {@code observed_sats} aligned with the blockchain for on-chain custody kinds.
 *
 * <ul>
 *   <li>{@link KfeWalletKind#WATCH_ONLY} — cold wallet: only chain balance is meaningful.
 *       Never treats internal available/locked as spendable truth; zeros any stray ledger
 *       buckets and sets observed from UTXO/descriptor scans.</li>
 *   <li>{@link KfeWalletKind#CUSTODIAL_ONCHAIN} — dual model: internal ledger
 *       (available/locked) authorizes spends; {@code observed_sats} mirrors chain for
 *       reconciliation/display.</li>
 *   <li>{@link KfeWalletKind#INTERNAL} — not scanned (pooled / ledger-only).</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "kfe.onchain-balance-sync.enabled", havingValue = "true", matchIfMissing = true)
public class KfeOnchainBalanceSyncService {

    private static final Logger log = LoggerFactory.getLogger(KfeOnchainBalanceSyncService.class);
    private static final String ASSET_BTC = "BTC";
    private static final List<KfeWalletKind> CHAIN_SYNC_KINDS = List.of(
            KfeWalletKind.WATCH_ONLY,
            KfeWalletKind.CUSTODIAL_ONCHAIN);

    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final KfeBalanceService balanceService;
    private final ObjectProvider<BlockchainClient> blockchainClient;
    private final KfeDashboardPublisher dashboardPublisher;
    private final TransactionTemplate transactionTemplate;
    private final source.common.service.AddressDerivationService addressDerivationService;
    private final int batchSize;
    private final int descriptorRange;

    public KfeOnchainBalanceSyncService(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeBalanceService balanceService,
            ObjectProvider<BlockchainClient> blockchainClient,
            KfeDashboardPublisher dashboardPublisher,
            TransactionTemplate transactionTemplate,
            source.common.service.AddressDerivationService addressDerivationService,
            @Value("${kfe.onchain-balance-sync.batch-size:50}") int batchSize,
            @Value("${kfe.onchain-balance-sync.descriptor-range:1000}") int descriptorRange) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.balanceService = balanceService;
        this.blockchainClient = blockchainClient;
        this.dashboardPublisher = dashboardPublisher;
        this.transactionTemplate = transactionTemplate;
        this.addressDerivationService = addressDerivationService;
        this.batchSize = Math.max(1, batchSize);
        this.descriptorRange = Math.max(1, descriptorRange);
    }

    @Scheduled(
            fixedDelayString = "${kfe.onchain-balance-sync.fixed-delay-ms:45000}",
            initialDelayString = "${kfe.onchain-balance-sync.initial-delay-ms:25000}")
    public void reconcileActiveOnchainWallets() {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }
        List<KfeWalletEntity> wallets = walletRepository
                .findByKindInAndStatus(CHAIN_SYNC_KINDS, KfeWalletStatus.ACTIVE);
        int limit = Math.min(batchSize, wallets.size());
        for (int i = 0; i < limit; i++) {
            try {
                syncWallet(wallets.get(i).getId());
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Onchain Balance] sync failed walletId={}: {}",
                        wallets.get(i).getId(),
                        exception.getMessage());
            }
        }
    }

    /**
     * Probe the chain and write absolute {@code observed_sats}. Safe to call after import or inbound detect.
     * Uses {@link TransactionTemplate} (not self-invoked {@code @Transactional}) so scheduled
     * reconcile and FOR UPDATE balance locks always run inside a real transaction.
     */
    public long syncWallet(UUID walletId) {
        Long result = transactionTemplate.execute(status -> doSyncWallet(walletId));
        return result == null ? -1L : result;
    }

    private long doSyncWallet(UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        if (!CHAIN_SYNC_KINDS.contains(wallet.getKind())) {
            return 0L;
        }
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            log.debug("[KFE Onchain Balance] blockchain client unavailable walletId={}", walletId);
            return -1L;
        }

        long chainSats = probeChainBalanceSats(client, wallet);
        balanceService.setObserved(walletId, ASSET_BTC, chainSats);

        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
            balanceService.zeroSpendableBucketsIfNeeded(walletId, ASSET_BTC);
        }

        dashboardPublisher.publishAfterCommit(wallet.getUserId());
        log.debug(
                "[KFE Onchain Balance] walletId={} kind={} chainSats={}",
                walletId,
                wallet.getKind(),
                chainSats);
        return chainSats;
    }

    long probeChainBalanceSats(BlockchainClient client, KfeWalletEntity wallet) {
        // Prefer known addresses first (addr() scantxoutset / listunspent). Descriptor
        // xpub scans are heavier and historically timed out / rejected mainnet xpub on
        // testnet4 when version bytes were wrong.
        long fromAddresses = probeAddressBalance(client, wallet.getId());
        long fromDescriptor = 0L;
        try {
            fromDescriptor = probeDescriptorBalance(client, wallet);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Onchain Balance] descriptor probe failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
        return Math.max(fromDescriptor, fromAddresses);
    }

    private long probeDescriptorBalance(BlockchainClient client, KfeWalletEntity wallet) {
        String receive = resolveReceiveDescriptor(wallet);
        if (receive == null) {
            return 0L;
        }
        long total = client.getConfirmedBalanceForDescriptor(receive, descriptorRange);
        String change = toChangeDescriptor(receive);
        if (change != null) {
            total = Math.addExact(total, client.getConfirmedBalanceForDescriptor(change, descriptorRange));
        }
        return total;
    }

    private long probeAddressBalance(BlockchainClient client, UUID walletId) {
        List<String> addresses = addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId).stream()
                .map(KfeWalletAddressEntity::getAddress)
                .filter(address -> address != null && !address.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (addresses.isEmpty()) {
            return 0L;
        }
        // listunspent only sees Core-wallet-imported scripts; scantxoutset(addr) always works.
        long fromListUnspent = client.getUnspentBalanceForAddresses(addresses);
        long fromScan = 0L;
        for (String address : addresses) {
            try {
                fromScan = Math.addExact(fromScan, client.getConfirmedBalanceForAddress(address));
            } catch (RuntimeException ignored) {
                // keep partial
            }
        }
        return Math.max(fromListUnspent, fromScan);
    }

    private String resolveReceiveDescriptor(KfeWalletEntity wallet) {
        // Prefer xpub rebuild when stored descriptor is truncated (legacy 128-byte pad).
        if (hasText(wallet.getDescriptor())) {
            String stored = rewriteDescriptorXpubs(stripChecksum(wallet.getDescriptor().trim()));
            if (isUsableOutputDescriptor(stored)) {
                return stored;
            }
            log.warn(
                    "[KFE Onchain Balance] stored descriptor unusable walletId={} len={}; rebuild from xpub",
                    wallet.getId(),
                    stored.length());
        }
        if (!hasText(wallet.getXpub())) {
            return null;
        }
        String xpub = addressDerivationService.toNetworkExtendedPublicKey(wallet.getXpub().trim());
        String fingerprint = hasText(wallet.getFingerprint())
                ? wallet.getFingerprint().trim().toLowerCase(Locale.ROOT)
                : "00000000";
        String accountPath = hasText(wallet.getDerivationPath())
                ? wallet.getDerivationPath().trim().replaceFirst("^m/", "").replace("'", "h")
                : "84h/0h/0h";
        String origin = accountPath.isEmpty() || "m".equalsIgnoreCase(accountPath)
                ? fingerprint
                : fingerprint + "/" + accountPath;
        return "wpkh([" + origin + "]" + xpub + "/0/*)";
    }

    private static boolean isUsableOutputDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return false;
        }
        String bare = stripChecksum(descriptor.trim());
        if (!bare.contains("(") || !bare.endsWith(")")) {
            return false;
        }
        return bare.contains("/*")
                || bare.startsWith("addr(")
                || bare.startsWith("raw(")
                || bare.matches(".*\\)/\\d+\\)$");
    }

    private String rewriteDescriptorXpubs(String descriptor) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([xtyzuv]pub[1-9A-HJ-NP-Za-km-z]{20,})\\b");
        java.util.regex.Matcher matcher = pattern.matcher(descriptor);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            String rewritten;
            try {
                rewritten = addressDerivationService.toNetworkExtendedPublicKey(raw);
            } catch (RuntimeException ignored) {
                rewritten = raw;
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rewritten));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String toChangeDescriptor(String receiveDescriptor) {
        if (receiveDescriptor == null || !receiveDescriptor.contains("/0/*")) {
            return null;
        }
        return receiveDescriptor.replace("/0/*", "/1/*");
    }

    private static String stripChecksum(String descriptor) {
        int hash = descriptor.indexOf('#');
        return hash >= 0 ? descriptor.substring(0, hash) : descriptor;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
