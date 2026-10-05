package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;

public interface WalletChangeAddressPort {
    String issueChangeAddress(WalletSnapshot wallet);
}
