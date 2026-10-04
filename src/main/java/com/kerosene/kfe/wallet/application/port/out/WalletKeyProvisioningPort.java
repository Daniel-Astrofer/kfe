package com.kerosene.kfe.wallet.application.port.out;

import java.util.UUID;

public interface WalletKeyProvisioningPort {
    String provision(UUID walletId, long ownerId);
}
