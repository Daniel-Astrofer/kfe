package source.kfe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import source.kfe.rail.BitcoinCoreRpcClient;
import source.kfe.service.KfeSystemWalletService;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class KfeBitcoinRuntimeBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KfeBitcoinRuntimeBootstrap.class);

    private final KfeSystemWalletService systemWalletService;
    private final BitcoinCoreRpcClient bitcoinCoreRpcClient;
    private final boolean bitcoinRpcEnabled;
    private final boolean bitcoinRpcRequired;
    private final boolean validateNetwork;
    private final boolean requireSynced;
    private final boolean bootstrapRpcWallets;
    private final String configuredNetwork;
    private final String primaryWalletName;
    private final String fundsWalletName;
    private final String profitWalletName;

    public KfeBitcoinRuntimeBootstrap(
            KfeSystemWalletService systemWalletService,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Value("${bitcoin.rpc.enabled:false}") boolean bitcoinRpcEnabled,
            @Value("${bitcoin.rpc.required:false}") boolean bitcoinRpcRequired,
            @Value("${kfe.bitcoin.validate-network-enabled:true}") boolean validateNetwork,
            @Value("${kfe.bitcoin.require-synced-enabled:false}") boolean requireSynced,
            @Value("${kfe.bitcoin-core.wallets.bootstrap-enabled:true}") boolean bootstrapRpcWallets,
            @Value("${bitcoin.network:mainnet}") String configuredNetwork,
            @Value("${bitcoin.rpc.wallet:}") String primaryWalletName,
            @Value("${kfe.bitcoin-core.wallets.funds:kerosene-funds}") String fundsWalletName,
            @Value("${kfe.bitcoin-core.wallets.profit:kerosene-profit}") String profitWalletName) {
        this.systemWalletService = systemWalletService;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient.getIfAvailable();
        this.bitcoinRpcEnabled = bitcoinRpcEnabled;
        this.bitcoinRpcRequired = bitcoinRpcRequired;
        this.validateNetwork = validateNetwork;
        this.requireSynced = requireSynced;
        this.bootstrapRpcWallets = bootstrapRpcWallets;
        this.configuredNetwork = configuredNetwork;
        this.primaryWalletName = primaryWalletName;
        this.fundsWalletName = fundsWalletName;
        this.profitWalletName = profitWalletName;
    }

    @Override
    public void run(ApplicationArguments args) {
        KfeSystemWalletService.SystemWallets systemWallets = systemWalletService.ensureSystemWallets();
        log.info(
                "KFE system wallets ready fundsWalletId={} profitWalletId={}",
                systemWallets.fundsWalletId(),
                systemWallets.profitWalletId());

        if (!bitcoinRpcEnabled) {
            return;
        }
        if (bitcoinCoreRpcClient == null) {
            if (bitcoinRpcRequired) {
                throw new IllegalStateException("bitcoin.rpc.enabled=true but Bitcoin Core RPC client is unavailable.");
            }
            return;
        }

        if (validateNetwork) {
            validateBitcoinCoreNetwork();
        }
        validateBitcoinCoreSyncState();
        if (bootstrapRpcWallets) {
            ensureRpcWalletsLoaded();
        }
    }

    private void validateBitcoinCoreNetwork() {
        String actualChain = bitcoinCoreRpcClient.chain();
        String expectedChain = expectedCoreChain(configuredNetwork);
        if (!expectedChain.equals(actualChain)) {
            throw new IllegalStateException(
                    "Bitcoin Core chain mismatch: expected " + expectedChain + " from bitcoin.network="
                            + configuredNetwork + " but node reported " + actualChain + ".");
        }
    }

    private void validateBitcoinCoreSyncState() {
        JsonNode chainInfo = bitcoinCoreRpcClient.blockchainInfo();
        boolean initialBlockDownload = chainInfo.path("initialblockdownload").asBoolean(false);
        if (!initialBlockDownload) {
            return;
        }

        long blocks = chainInfo.path("blocks").asLong(-1L);
        long headers = chainInfo.path("headers").asLong(-1L);
        double progress = chainInfo.path("verificationprogress").asDouble(0.0D);
        String message = "Bitcoin Core is still in initial block download"
                + " chain=" + chainInfo.path("chain").asText("unknown")
                + " blocks=" + blocks
                + " headers=" + headers
                + " verificationProgress=" + progress + ".";
        if (requireSynced) {
            throw new IllegalStateException(message);
        }
        log.warn("{} Payment request reconciliation may not observe recent on-chain payments until sync completes.", message);
    }

    private void ensureRpcWalletsLoaded() {
        for (String walletName : walletNames()) {
            bitcoinCoreRpcClient.ensureWalletLoaded(walletName);
            log.info("Bitcoin Core wallet loaded wallet={}", walletName);
        }
    }

    private Set<String> walletNames() {
        Set<String> wallets = new LinkedHashSet<>();
        addWallet(wallets, primaryWalletName);
        addWallet(wallets, fundsWalletName);
        addWallet(wallets, profitWalletName);
        return wallets;
    }

    private void addWallet(Set<String> wallets, String walletName) {
        if (walletName != null && !walletName.isBlank()) {
            wallets.add(walletName.trim());
        }
    }

    private String expectedCoreChain(String network) {
        String normalized = network != null ? network.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "main", "mainnet" -> "main";
            case "testnet", "testnet3" -> "test";
            case "testnet4" -> "testnet4";
            case "signet" -> "signet";
            case "regtest" -> "regtest";
            default -> throw new IllegalStateException("Unsupported bitcoin.network value: " + network);
        };
    }
}
