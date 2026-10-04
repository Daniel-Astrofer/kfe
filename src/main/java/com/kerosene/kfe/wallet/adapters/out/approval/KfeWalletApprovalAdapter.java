package com.kerosene.kfe.wallet.adapters.out.approval;

import java.time.Instant;
import org.springframework.stereotype.Component;
import com.kerosene.common.financial.approval.DeviceProof;
import com.kerosene.common.financial.approval.FinancialTransactionApprovalPort;
import com.kerosene.kfe.wallet.application.port.out.WalletApprovalPort;

@Component
public final class KfeWalletApprovalAdapter implements WalletApprovalPort {
    private final FinancialTransactionApprovalPort approval;

    public KfeWalletApprovalAdapter(FinancialTransactionApprovalPort approval) {
        this.approval = approval;
    }

    @Override
    public void approveColdPsbt(long ownerId, String factor) {
        if (factor != null && !factor.isBlank()) {
            approval.approveColdWalletPsbt(
                    ownerId,
                    new DeviceProof("wallet-cold-signer", factor, "kfe-cold-wallet", Instant.now()));
        }
    }
}
