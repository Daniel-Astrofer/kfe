package com.kerosene.kfe.wallet.adapters.out.vault;

import org.springframework.stereotype.Service;
import com.kerosene.common.financial.operations.FinancialMpcKeyPort;
import com.kerosene.kfe.wallet.application.port.out.WalletKeyProvisioningPort;

import java.util.UUID;

@Service
public class KfeMpcKeyService implements WalletKeyProvisioningPort {

    private final FinancialMpcKeyPort mpcKeyPort;

    public KfeMpcKeyService(FinancialMpcKeyPort mpcKeyPort) {
        this.mpcKeyPort = mpcKeyPort;
    }

    public String keygenWallet(UUID walletId, Long userId) {
        return mpcKeyPort.keygenWallet(walletId, userId);
    }

    @Override
    public String provision(UUID walletId, long ownerId) {
        return keygenWallet(walletId, ownerId);
    }
}
