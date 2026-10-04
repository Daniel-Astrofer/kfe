package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;
import java.util.UUID;

public interface WalletWorkflowPort {
    UUID create(
            long ownerId,
            UUID walletId,
            String psbt,
            String psbtHash,
            long feeSats,
            long amountSats,
            String destinationAddress,
            List<Outpoint> inputs);
}
