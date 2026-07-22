package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

import java.time.Instant;
import java.util.UUID;

/**
 * Fire-and-forget bridge: builds a vault-mesh Intent and submits via the port.
 * Does not replace rail executors / mpc-sidecar (F4 dual-path safe).
 */
@Service
public class KfeVaultMeshIntentService {

    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshIntentService.class);

    private final VaultMeshSettlementPort settlementPort;
    private final boolean submitOnOutbound;
    private final String defaultBucket;

    public KfeVaultMeshIntentService(
            VaultMeshSettlementPort settlementPort,
            @Value("${kfe.vaultmesh.submit-on-outbound:false}") boolean submitOnOutbound,
            @Value("${kfe.vaultmesh.default-bucket:USERS}") String defaultBucket) {
        this.settlementPort = settlementPort;
        this.submitOnOutbound = submitOnOutbound;
        this.defaultBucket = defaultBucket;
    }

    public VaultMeshReceipt submitOutboundIntent(
            UUID transactionId,
            String destination,
            long amountSats,
            String policyHash) {
        VaultMeshIntent intent = new VaultMeshIntent(
                transactionId == null ? UUID.randomUUID().toString() : transactionId.toString(),
                defaultBucket,
                destination == null ? "" : destination,
                amountSats,
                policyHash == null ? "" : policyHash,
                Instant.now().toEpochMilli());
        VaultMeshReceipt receipt = settlementPort.submitIntent(intent);
        log.info(
                "vault_mesh_intent intentId={} status={} reason={}",
                receipt.intentId(),
                receipt.status(),
                receipt.reasonCode());
        return receipt;
    }

    public boolean isSubmitOnOutboundEnabled() {
        return submitOnOutbound;
    }
}
