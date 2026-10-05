package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.AddressSnapshot;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;

import java.util.List;

public interface WalletChainPort {
    List<UtxoSnapshot> listUnspent(
            WalletSnapshot wallet,
            List<AddressSnapshot> activeAddresses,
            int descriptorScanRange);
}
