package com.kerosene.kfe.rail;

import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

/**
 * Mesh CHANNELS inject: decision-gate is non-mutating; execution soft-reserves via
 * {@code POST /v1/intent/reserve} with allowlisted destination {@code ln-channel-rebalance}.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public class VaultMeshChannelsMeshInjectGateway implements ChannelsMeshInjectGateway {

    private static final String BUCKET_CHANNELS = "CHANNELS";
    /** Mesh allowlist tag for CHANNELS bucket (not LN peer pubkey). */
    static final String CHANNELS_DESTINATION = "ln-channel-rebalance";

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
        // Decision-gate only — no ledger mutation. Capital is reserved at open execution.
        return InjectResult.ok("CHANNELS_INJECT_READY");
    }

    @Override
    public DebitResult reserveOpen(String intentId, long amountSats, String peerPubkey) {
        InjectResult gate = authorizeOpen(amountSats, peerPubkey);
        if (!gate.authorized()) {
            return DebitResult.refuse(gate.reasonCode());
        }
        if (intentId == null || intentId.isBlank()) {
            return DebitResult.refuse("CHANNELS_INJECT_MISSING_INTENT_ID");
        }

        VaultMeshIntent intent =
                new VaultMeshIntent(
                        intentId.trim(),
                        BUCKET_CHANNELS,
                        CHANNELS_DESTINATION,
                        amountSats,
                        "",
                        Instant.now().toEpochMilli());

        VaultMeshReceipt receipt;
        try {
            receipt = settlementPort.reserveIntent(intent);
        } catch (RuntimeException ex) {
            return DebitResult.refuse(
                    "CHANNELS_INJECT_VAULT_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }

        if (receipt == null) {
            return DebitResult.refuse("CHANNELS_INJECT_NULL_RECEIPT");
        }
        if (receipt.status() != VaultMeshReceipt.Status.ACCEPTED) {
            return DebitResult.refuse(
                    "CHANNELS_INJECT_RESERVE_REJECTED:"
                            + (receipt.reasonCode() == null ? "UNKNOWN" : receipt.reasonCode()));
        }
        String reservedId = receipt.intentId() == null || receipt.intentId().isBlank()
                ? intentId.trim()
                : receipt.intentId();
        return DebitResult.ok(reservedId, "CHANNELS_INJECT_RESERVED:" + reservedId);
    }

    @Override
    public InjectResult releaseOpen(String intentId, long amountSats, String peerPubkey) {
        if (intentId == null || intentId.isBlank()) {
            return InjectResult.refuse("CHANNELS_INJECT_MISSING_INTENT_ID");
        }
        VaultMeshReceipt receipt;
        try {
            receipt = settlementPort.releaseIntent(intentId.trim(), BUCKET_CHANNELS, amountSats);
        } catch (RuntimeException ex) {
            return InjectResult.refuse(
                    "CHANNELS_INJECT_RELEASE_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
        if (receipt == null) {
            return InjectResult.refuse("CHANNELS_INJECT_RELEASE_NULL_RECEIPT");
        }
        if (receipt.status() != VaultMeshReceipt.Status.ACCEPTED) {
            return InjectResult.refuse(
                    "CHANNELS_INJECT_RELEASE_REJECTED:"
                            + (receipt.reasonCode() == null ? "UNKNOWN" : receipt.reasonCode()));
        }
        return InjectResult.ok(
                "CHANNELS_INJECT_RELEASED:" + intentId.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public InjectResult commitOpen(String intentId) {
        if (intentId == null || intentId.isBlank()) {
            return InjectResult.refuse("CHANNELS_INJECT_MISSING_INTENT_ID");
        }
        VaultMeshReceipt receipt;
        try {
            receipt = settlementPort.commitIntent(intentId.trim());
        } catch (RuntimeException ex) {
            return InjectResult.refuse(
                    "CHANNELS_INJECT_COMMIT_HTTP_ERROR:" + ex.getClass().getSimpleName());
        }
        if (receipt == null) {
            return InjectResult.refuse("CHANNELS_INJECT_COMMIT_NULL_RECEIPT");
        }
        if (receipt.status() != VaultMeshReceipt.Status.ACCEPTED) {
            return InjectResult.refuse(
                    "CHANNELS_INJECT_COMMIT_REJECTED:"
                            + (receipt.reasonCode() == null ? "UNKNOWN" : receipt.reasonCode()));
        }
        return InjectResult.ok("CHANNELS_INJECT_COMMITTED:" + intentId.trim());
    }
}
