package com.kerosene.kfe.rail;

import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnBean(VaultMeshSettlementPort.class)
public class VaultMeshChannelsMeshInjectGateway implements ChannelsMeshInjectGateway {

    private static final String BUCKET_CHANNELS = "CHANNELS";

    private final VaultMeshSettlementPort settlementPort;

    public VaultMeshChannelsMeshInjectGateway(VaultMeshSettlementPort settlementPort) {
        this.settlementPort = settlementPort;
    }

    @Override
    public InjectResult authorizeOpen(long amountSats, String peerPubkey) {
        if (amountSats <= 0L) {
            return InjectResult.refuse("CHANNELS_INJECT_INVALID_AMOUNT");
        }
        if (peerPubkey == null || peerPubkey.isBlank()) {
            return InjectResult.refuse("CHANNELS_INJECT_MISSING_PEER");
        }

        String intentId = "channels-inject-open-" + UUID.randomUUID();
        VaultMeshIntent intent =
                new VaultMeshIntent(
                        intentId,
                        BUCKET_CHANNELS,
                        peerPubkey.trim().toLowerCase(Locale.ROOT),
                        amountSats,
                        "",
                        Instant.now().toEpochMilli());

        VaultMeshReceipt receipt;
        try {
            receipt = settlementPort.submitIntent(intent);
        } catch (RuntimeException ex) {
            return InjectResult.refuse(
                    "CHANNELS_INJECT_VAULT_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }

        if (receipt == null) {
            return InjectResult.refuse("CHANNELS_INJECT_NULL_RECEIPT");
        }
        if (receipt.status() != VaultMeshReceipt.Status.ACCEPTED) {
            return InjectResult.refuse("CHANNELS_INJECT_REJECTED:" + receipt.reasonCode());
        }
        return InjectResult.ok("CHANNELS_INJECT_ACCEPTED:" + receipt.intentId());
    }
}

