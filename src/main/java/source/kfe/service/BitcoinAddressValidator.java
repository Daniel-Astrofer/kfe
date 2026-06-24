package source.kfe.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class BitcoinAddressValidator {
    private static final Pattern BASE58_ADDRESS = Pattern.compile("^[123mn2][123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{25,34}$");
    private static final Pattern BECH32_ADDRESS = Pattern.compile("^[023456789acdefghjklmnpqrstuvwxyz]{6,87}$");

    private final String bitcoinNetwork;

    public BitcoinAddressValidator(@Value("${bitcoin.network:mainnet}") String bitcoinNetwork) {
        this.bitcoinNetwork = bitcoinNetwork;
    }

    public boolean isValidBitcoinAddressForConfiguredNetwork(String address) {
        String candidate = address != null ? address.trim() : "";
        if (candidate.isEmpty() || candidate.length() > 90) {
            return false;
        }
        BitcoinNetwork network = configuredBitcoinNetwork();
        String lower = candidate.toLowerCase();
        if (lower.startsWith(network.bech32Prefix)) {
            return isValidBech32Address(candidate, network.bech32Prefix);
        }
        return isValidBase58Address(candidate, network);
    }

    private BitcoinNetwork configuredBitcoinNetwork() {
        String normalized = bitcoinNetwork != null ? bitcoinNetwork.trim().toLowerCase() : "mainnet";
        return switch (normalized) {
            case "main", "mainnet", "livenet" -> BitcoinNetwork.MAINNET;
            case "test", "testnet", "testnet3", "signet" -> BitcoinNetwork.TESTNET;
            case "regtest", "reg" -> BitcoinNetwork.REGTEST;
            default -> throw new IllegalStateException("Unsupported bitcoin.network: " + bitcoinNetwork);
        };
    }

    private boolean isValidBech32Address(String address, String expectedPrefix) {
        boolean allLower = address.equals(address.toLowerCase());
        boolean allUpper = address.equals(address.toUpperCase());
        if (!allLower && !allUpper) {
            return false;
        }
        String lower = address.toLowerCase();
        if (!lower.startsWith(expectedPrefix) || lower.length() < expectedPrefix.length() + 8) {
            return false;
        }
        String payload = lower.substring(expectedPrefix.length());
        return BECH32_ADDRESS.matcher(payload).matches();
    }

    private boolean isValidBase58Address(String address, BitcoinNetwork network) {
        if (!BASE58_ADDRESS.matcher(address).matches()) {
            return false;
        }
        char prefix = address.charAt(0);
        return switch (network) {
            case MAINNET -> prefix == '1' || prefix == '3';
            case TESTNET -> prefix == 'm' || prefix == 'n' || prefix == '2';
            case REGTEST -> false;
        };
    }

    private enum BitcoinNetwork {
        MAINNET("bc1"),
        TESTNET("tb1"),
        REGTEST("bcrt1");

        private final String bech32Prefix;

        BitcoinNetwork(String bech32Prefix) {
            this.bech32Prefix = bech32Prefix;
        }
    }
}
