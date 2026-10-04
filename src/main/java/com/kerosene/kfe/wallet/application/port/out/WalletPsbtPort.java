package com.kerosene.kfe.wallet.application.port.out;

import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;

public interface WalletPsbtPort {
    FundedPsbt create(
            List<Outpoint> inputs,
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            String changeAddress);

    record FundedPsbt(String psbt, long feeSats) {
    }
}
