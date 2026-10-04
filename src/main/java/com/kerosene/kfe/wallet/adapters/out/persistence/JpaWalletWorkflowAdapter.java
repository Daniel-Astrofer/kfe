package com.kerosene.kfe.wallet.adapters.out.persistence;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfePsbtWorkflowEntity;
import com.kerosene.kfe.wallet.adapters.in.compatibility.KfePsbtWorkflowService;
import com.kerosene.kfe.wallet.application.port.out.WalletWorkflowPort;
import com.kerosene.kfe.wallet.domain.model.Outpoint;

import java.util.List;
import java.util.UUID;

@Component
public final class JpaWalletWorkflowAdapter implements WalletWorkflowPort {
    private final KfePsbtWorkflowService workflows;

    public JpaWalletWorkflowAdapter(KfePsbtWorkflowService workflows) {
        this.workflows = workflows;
    }

    @Override
    public UUID create(
            long ownerId,
            UUID walletId,
            String psbt,
            String psbtHash,
            long feeSats,
            long amountSats,
            String destinationAddress,
            List<Outpoint> inputs) {
        List<KfeColdWalletPsbtRequest.Input> legacyInputs = inputs.stream()
                .map(input -> new KfeColdWalletPsbtRequest.Input(input.txid(), input.vout()))
                .toList();
        KfePsbtWorkflowEntity workflow = workflows.create(
                ownerId,
                walletId,
                psbt,
                psbtHash,
                feeSats,
                amountSats,
                destinationAddress,
                legacyInputs);
        return workflow.getId();
    }
}
