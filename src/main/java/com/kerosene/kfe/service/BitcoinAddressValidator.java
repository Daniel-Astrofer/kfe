package com.kerosene.kfe.service;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;

@Component
public class BitcoinAddressValidator {
    private final String bitcoinNetwork;
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider;

    public BitcoinAddressValidator(
            @Value("${bitcoin.network:mainnet}") String bitcoinNetwork,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider) {
        this.bitcoinNetwork = bitcoinNetwork;
        this.bitcoinCoreProvider = bitcoinCoreProvider;
    }

    public boolean isValidBitcoinAddressForConfiguredNetwork(String address) {
        String candidate = address != null ? address.trim() : "";
        if (candidate.isEmpty() || candidate.length() > 90) {
            return false;
        }
        try {
            NetworkParameters network = configuredBitcoinNetwork();
            Address.fromString(network, candidate);
            return true;
        } catch (AddressFormatException exception) {
            return validateNewerSegwitWithBitcoinCore(candidate);
        }
    }

    private boolean validateNewerSegwitWithBitcoinCore(String candidate) {
        NetworkParameters network = configuredBitcoinNetwork();
        String lower = candidate.toLowerCase();
        boolean consistentCase = candidate.equals(lower) || candidate.equals(candidate.toUpperCase());
        if (!consistentCase || !lower.startsWith(network.getSegwitAddressHrp() + "1")) {
            return false;
        }
        try {
            BitcoinCoreRpcClient bitcoinCore = bitcoinCoreProvider.getIfAvailable();
            if (bitcoinCore == null) {
                return false;
            }
            return bitcoinCore.isValidAddress(candidate);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private NetworkParameters configuredBitcoinNetwork() {
        String normalized = bitcoinNetwork != null ? bitcoinNetwork.trim().toLowerCase() : "mainnet";
        return switch (normalized) {
            case "main", "mainnet", "livenet" -> MainNetParams.get();
            case "test", "testnet", "testnet3", "testnet4", "signet" -> TestNet3Params.get();
            case "regtest", "reg" -> RegTestParams.get();
            default -> throw new IllegalStateException("Unsupported bitcoin.network: " + bitcoinNetwork);
        };
    }
}
