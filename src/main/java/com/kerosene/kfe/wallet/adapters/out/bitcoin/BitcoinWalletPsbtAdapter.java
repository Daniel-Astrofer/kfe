package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.kerosene.common.exception.FinancialProviderUnavailableException;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.wallet.application.port.out.WalletPsbtPort;
import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;

@Component
public final class BitcoinWalletPsbtAdapter implements WalletPsbtPort {
    private final ObjectProvider<BitcoinCoreRpcClient> provider;

    public BitcoinWalletPsbtAdapter(ObjectProvider<BitcoinCoreRpcClient> provider) {
        this.provider = provider;
    }

    @Override
    public FundedPsbt create(
            List<Outpoint> inputs,
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            String changeAddress) {
        BitcoinCoreRpcClient core = provider.getIfAvailable();
        if (core == null) {
            throw new FinancialProviderUnavailableException(
                    "Bitcoin Core RPC is unavailable for KFE PSBT creation.");
        }
        BitcoinCoreRpcClient.FundedPsbt funded = core.createWatchOnlyPsbt(
                inputs.stream()
                        .map(input -> new BitcoinCoreRpcClient.PsbtInput(input.txid(), input.vout()))
                        .toList(),
                destinationAddress,
                amountSats,
                confirmationTarget,
                feeRateSatsPerVbyte,
                changeAddress);
        return new FundedPsbt(funded.psbt(), funded.feeSats());
    }
}
