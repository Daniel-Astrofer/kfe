package com.kerosene.kfe.rail;

import com.kerosene.common.vaultmesh.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.VaultMeshPsbtRequest;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

/**
 * Mesh CHANNELS inject: soft-reserve → on-chain CHANNELS Taproot PSBT to LND address →
 * openChannel → Intent commit.
 *
 * <p>CHANNELS uses a <strong>dedicated</strong> Taproot key ({@code GET /v1/bitcoin/deposit?bucket=CHANNELS}),
 * never the USERS omnibus {@code tb1p}.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public class VaultMeshChannelsMeshInjectGateway implements ChannelsMeshInjectGateway {

    private static final String BUCKET_CHANNELS = "CHANNELS";
    /** Mesh allowlist tag for CHANNELS bucket (not LN peer pubkey). */
    static final String CHANNELS_DESTINATION = "ln-channel-rebalance";

    private final VaultMeshSettlementPort settlementPort;
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    private final int fundConfTarget;
    private final long maxFundFeeSats;
    private final Long fundFeeRateSatVb;

    public VaultMeshChannelsMeshInjectGateway(
            VaultMeshSettlementPort settlementPort,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Value("${kfe.channel.mesh-inject-fund-conf-target:1}") int fundConfTarget,
            @Value("${kfe.channel.mesh-inject-fund-max-fee-sats:50000}") long maxFundFeeSats,
            @Value("${kfe.channel.mesh-inject-fund-fee-rate-sat-vb:0}") long fundFeeRateSatVb) {
        this.settlementPort = settlementPort;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.fundConfTarget = Math.max(1, fundConfTarget);
        this.maxFundFeeSats = Math.max(0L, maxFundFeeSats);
        this.fundFeeRateSatVb = fundFeeRateSatVb > 0L ? fundFeeRateSatVb : null;
    }

    @Override
    public InjectResult authorizeOpen(long amountSats, String peerPubkey) {
        if (amountSats <= 0L) {
            return InjectResult.refuse("CHANNELS_INJECT_INVALID_AMOUNT");
        }
        if (peerPubkey == null || peerPubkey.isBlank()) {
            return InjectResult.refuse("CHANNELS_INJECT_MISSING_PEER");
        }
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
        if (!isPlausibleBitcoinAddress(addr)) {
            return FundResult.refuse("CHANNELS_INJECT_INVALID_LND_ADDRESS");
        }

        BitcoinCoreRpcClient bitcoinCore = bitcoinCoreRpcClient.getIfAvailable();
        if (bitcoinCore == null) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_NO_BITCOIN_CORE");
        }

        VaultMeshDepositInfo channelsDeposit = settlementPort.getChannelsDepositAddress();
        if (channelsDeposit == null
                || channelsDeposit.address() == null
                || channelsDeposit.address().isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_NO_CHANNELS_DEPOSIT");
        }
        VaultMeshDepositInfo usersDeposit = settlementPort.getUsersDepositAddress();
        if (usersDeposit != null
                && usersDeposit.address() != null
                && channelsDeposit.address().equalsIgnoreCase(usersDeposit.address().trim())) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_KEY_COLLISION_USERS");
        }

        try {
            if (channelsDeposit.descriptor() != null && !channelsDeposit.descriptor().isBlank()) {
                bitcoinCore.importWatchOnlyDescriptor(channelsDeposit.descriptor(), null);
            }
        } catch (RuntimeException ex) {
            // Descriptor may already be imported; continue to PSBT build (fail later if unfunded).
        }

        BitcoinCoreRpcClient.FundedPsbt funded;
        try {
            funded = bitcoinCore.createFundedPsbt(
                    addr, amountSats, fundConfTarget, fundFeeRateSatVb, "bech32m");
        } catch (RuntimeException ex) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_PSBT_BUILD_FAILED:" + ex.getClass().getSimpleName());
        }
        if (funded == null || funded.psbt() == null || funded.psbt().isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_EMPTY_PSBT");
        }
        if (funded.feeSats() > maxFundFeeSats) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_FEE_CAP:" + funded.feeSats() + ">" + maxFundFeeSats);
        }

        String sessionId = "btc-channels-fund-" + intentId.trim().toLowerCase(Locale.ROOT);
        VaultMeshPsbtReceipt signed = settlementPort.signPsbt(
                new VaultMeshPsbtRequest(
                        intentId.trim(),
                        sessionId,
                        BUCKET_CHANNELS,
                        addr,
                        amountSats,
                        funded.psbt(),
                        Boolean.FALSE));
        if (signed.status() == VaultMeshReceipt.Status.FAIL_STOP) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_MESH_FAIL_STOP:"
                            + (signed.reasonCode() == null ? "UNKNOWN" : signed.reasonCode()));
        }
        if (signed.status() != VaultMeshReceipt.Status.ACCEPTED
                || signed.signedPsbt() == null
                || signed.signedPsbt().isBlank()) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_MESH_SIGN_REFUSED:"
                            + (signed.reasonCode() == null ? "UNKNOWN" : signed.reasonCode()));
        }

        BitcoinCoreRpcClient.FinalizedPsbt finalized;
        try {
            finalized = bitcoinCore.finalizePsbt(signed.signedPsbt());
        } catch (RuntimeException ex) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_FINALIZE_FAILED:" + ex.getClass().getSimpleName());
        }
        if (finalized == null
                || !finalized.complete()
                || finalized.hex() == null
                || finalized.hex().isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_FINALIZE_INCOMPLETE");
        }

        String txid;
        try {
            txid = bitcoinCore.sendRawTransaction(finalized.hex());
        } catch (RuntimeException ex) {
            return FundResult.refuse(
                    "CHANNELS_INJECT_FUND_BROADCAST_FAILED:" + ex.getClass().getSimpleName());
        }
        if (txid == null || txid.isBlank()) {
            return FundResult.refuse("CHANNELS_INJECT_FUND_BROADCAST_NO_TXID");
        }

        return FundResult.ok(
                txid.trim(),
                "CHANNELS_INJECT_FUNDED_ONCHAIN:" + txid.trim() + ":" + addr);
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

    private static boolean isPlausibleBitcoinAddress(String addr) {
        String lower = addr.toLowerCase(Locale.ROOT);
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
            return false;
        }
        if (!bech32 && (lower.contains("-") || lower.contains("_") || lower.contains(" "))) {
            return false;
        }
        return true;
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
        return r.contains("intent replay")
                || r.contains("already consumed")
                || r.contains("already_committed")
                || r.contains("duplicate");
    }
}
