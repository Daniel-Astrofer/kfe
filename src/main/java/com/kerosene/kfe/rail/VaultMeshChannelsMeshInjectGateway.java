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
 * {@code POST /v1/intent/reserve} with allowlisted destination {@code ln-channel-rebalance},
 * binds an LND funding address, then commits after open.
 *
 * <p>On-chain CHANNELS→LND PSBT is not available (shared Taproot is USERS-only). The
 * fund step fail-closes only when the LND address is missing/invalid; residual: LND
 * wallet UTXOs still pay {@code openChannel}.
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
            // Idempotent resume: same decision id already reserved (or committed).
            if (isIdempotentReserve(receipt.reasonCode())) {
                return DebitResult.ok(
                        intentId.trim(),
                        "CHANNELS_INJECT_RESERVED_IDEMPOTENT:" + intentId.trim());
            }
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
    public FundResult fundOpen(String intentId, long amountSats, String lndFundingAddress) {
        if (intentId == null || intentId.isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_MISSING_INTENT_ID");
        }
        if (amountSats <= 0L) {
            return FundResult.refuse("CHANNELS_INJECT_INVALID_AMOUNT");
        }
        if (lndFundingAddress == null || lndFundingAddress.isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_MISSING_LND_ADDRESS");
        }
        String addr = lndFundingAddress.trim();
        String lower = addr.toLowerCase(Locale.ROOT);
        // Bech32 (bc1/tb1/bcrt1) or legacy base58 (1…/3… mainnet, m…/n…/2… testnet).
        boolean bech32 =
                lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1");
        boolean legacy =
                (lower.startsWith("1") || lower.startsWith("3") || lower.startsWith("m")
                                || lower.startsWith("n") || lower.startsWith("2"))
                        && lower.length() >= 26
                        && lower.chars().allMatch(c ->
                                (c >= '1' && c <= '9')
                                        || (c >= 'a' && c <= 'z')
                                        || (c >= 'A' && c <= 'Z'));
        if (!bech32 && !legacy) {
            return FundResult.refuse("CHANNELS_INJECT_INVALID_LND_ADDRESS");
        }
        // Reject obvious non-address tokens that only match a legacy prefix char.
        if (!bech32 && (lower.contains("-") || lower.contains("_") || lower.contains(" "))) {
            return FundResult.refuse("CHANNELS_INJECT_INVALID_LND_ADDRESS");
        }
        // Largest honest slice: bind withdraw target to reserved Intent. Residual:
        // no CHANNELS on-chain PSBT into LND (per-bucket key not shipped).
        return FundResult.ok(
                null,
                "CHANNELS_INJECT_FUND_BOUND:"
                        + intentId.trim().toLowerCase(Locale.ROOT)
                        + ":"
                        + addr);
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
            if (isIdempotentCommit(receipt.reasonCode())) {
                return InjectResult.ok("CHANNELS_INJECT_COMMITTED_IDEMPOTENT:" + intentId.trim());
            }
            return InjectResult.refuse(
                    "CHANNELS_INJECT_COMMIT_REJECTED:"
                            + (receipt.reasonCode() == null ? "UNKNOWN" : receipt.reasonCode()));
        }
        return InjectResult.ok("CHANNELS_INJECT_COMMITTED:" + intentId.trim());
    }

    private static boolean isIdempotentReserve(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return false;
        }
        String r = reasonCode.toLowerCase(Locale.ROOT);
        return r.contains("intent replay")
                || r.contains("already reserved")
                || r.contains("already_reserved")
                || r.contains("duplicate");
    }

    private static boolean isIdempotentCommit(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return false;
        }
        String r = reasonCode.toLowerCase(Locale.ROOT);
        // Already durable-consumed: commit retry is a no-op success.
        return r.contains("intent replay")
                || r.contains("already consumed")
                || r.contains("already_committed")
                || r.contains("duplicate");
    }
}
