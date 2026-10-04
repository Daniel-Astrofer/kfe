package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.kerosene.common.exception.FinancialProviderUnavailableException;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.wallet.application.port.out.WalletChainPort;
import com.kerosene.kfe.wallet.domain.model.AddressSnapshot;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;
import com.kerosene.kfe.wallet.domain.service.UtxoProjectionPolicy;

import java.util.List;
import java.util.Map;

@Component
public final class BitcoinWalletChainAdapter implements WalletChainPort {
    private final ObjectProvider<BlockchainClient> provider;

    public BitcoinWalletChainAdapter(ObjectProvider<BlockchainClient> provider) {
        this.provider = provider;
    }

    @Override
    public List<UtxoSnapshot> listUnspent(
            WalletSnapshot wallet,
            List<AddressSnapshot> activeAddresses,
            int descriptorScanRange) {
        BlockchainClient client = provider.getIfAvailable();
        if (client == null) {
            throw new FinancialProviderUnavailableException(
                    "Blockchain client is unavailable for KFE network data.");
        }
        Map<com.kerosene.kfe.wallet.domain.model.Outpoint, UtxoSnapshot> byOutpoint =
                UtxoProjectionPolicy.newProjection();
        if (activeAddresses != null) {
            for (AddressSnapshot address : activeAddresses) {
                for (BlockchainClient.AddressUtxo utxo :
                        client.getUnspentOutputsMerged(address.address())) {
                    add(byOutpoint, utxo, address.address());
                }
            }
        }
        // Descriptor scans are deliberately best-effort: known active addresses remain usable
        // if Core is busy with another scantxoutset request.
        if (wallet.isCold() && wallet.descriptorConfigured()) {
            try {
                for (BlockchainClient.AddressUtxo utxo : client.getUnspentOutputsFromScan(
                        wallet.descriptor(), descriptorScanRange)) {
                    add(byOutpoint, utxo, utxo.address());
                }
            } catch (RuntimeException ignored) {
                // Keep the address projection when the optional scan is unavailable.
            }
        }
        return List.copyOf(byOutpoint.values());
    }

    private static void add(
            Map<com.kerosene.kfe.wallet.domain.model.Outpoint, UtxoSnapshot> byOutpoint,
            BlockchainClient.AddressUtxo raw,
            String fallbackAddress) {
        if (raw == null || raw.txid() == null || raw.txid().isBlank()) {
            return;
        }
        UtxoSnapshot candidate = new UtxoSnapshot(
                new com.kerosene.kfe.wallet.domain.model.Outpoint(raw.txid(), raw.vout()),
                raw.valueSats(),
                raw.scriptPubKey(),
                hasText(raw.address()) ? raw.address() : fallbackAddress,
                raw.confirmations());
        UtxoProjectionPolicy.merge(byOutpoint, candidate);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
