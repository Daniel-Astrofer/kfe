package com.kerosene.kfe.wallet.adapters.out.bitcoin;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.BitcoinAddressValidator;
import com.kerosene.kfe.wallet.application.port.out.WalletDestinationPort;

@Component
public final class BitcoinWalletDestinationAdapter implements WalletDestinationPort {
    private final BitcoinAddressValidator validator;

    public BitcoinWalletDestinationAdapter(BitcoinAddressValidator validator) {
        this.validator = validator;
    }

    @Override
    public void requireValidBitcoinAddress(String address) {
        if (!validator.isValidBitcoinAddressForConfiguredNetwork(address)) {
            throw new IllegalArgumentException(
                    "destinationAddress is not valid for the configured Bitcoin network.");
        }
    }
}
