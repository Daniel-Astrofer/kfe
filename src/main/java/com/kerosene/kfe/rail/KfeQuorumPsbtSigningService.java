package com.kerosene.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.vaultmesh.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.VaultMeshPsbtRequest;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.infra.logging.LogSanitizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production PSBT signing for custodial on-chain outbounds.
 *
 * <p>Signers may be:
 * <ul>
 *   <li>Vault mesh Taproot FROST ({@code kfe.vaultmesh.mesh-only=true}) — Intent-gated
 *       {@code /v1/bitcoin/sign-psbt}; no mpc fallback</li>
 *   <li>Remote HTTP endpoints ({@code quorum.psbt.signer-urls}) — multiparty HSM/sidecar nodes</li>
 *   <li>Local Bitcoin Core wallet ({@code quorum.psbt.local-core-signer-enabled}) via
 *       {@code walletprocesspsbt} — first-class when keys live in Core (or as one quorum seat)</li>
 * </ul>
 *
 * Quorum requires {@code required-signatures} distinct successful signatures before
 * combine → finalize → broadcast (mesh-only path treats the mesh as the sole signer seat).
 */
@Service
public class KfeQuorumPsbtSigningService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KfeQuorumPsbtSigningService.class);

    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final VaultMeshSettlementPort vaultMeshSettlementPort;
    private final boolean meshOnly;
    private final String meshBucket;
    private final int requiredSignatures;
    private final int fundingConfirmationTarget;
    private final List<String> signerUrls;
    private final List<String> signerApiKeys;
    private final List<String> signerIds;
    private final boolean requireSignerIdentity;
    private final boolean localCoreSignerEnabled;
    private final String localCoreSignerId;

    public KfeQuorumPsbtSigningService(
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Qualifier("custodyRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            VaultMeshSettlementPort vaultMeshSettlementPort,
            @Value("${kfe.vaultmesh.mesh-only:false}") boolean meshOnly,
            @Value("${kfe.vaultmesh.default-bucket:USERS}") String meshBucket,
            @Value("${quorum.psbt.required-signatures:2}") int requiredSignatures,
            @Value("${quorum.psbt.funding-confirmation-target:6}") int fundingConfirmationTarget,
            @Value("${quorum.psbt.signer-urls:}") String signerUrls,
            @Value("${quorum.psbt.signer-api-keys:}") String signerApiKeys,
            @Value("${quorum.psbt.signer-ids:}") String signerIds,
            @Value("${quorum.psbt.require-signer-identity:true}") boolean requireSignerIdentity,
            @Value("${quorum.psbt.local-core-signer-enabled:false}") boolean localCoreSignerEnabled,
            @Value("${quorum.psbt.local-core-signer-id:bitcoin-core-wallet}") String localCoreSignerId) {
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.vaultMeshSettlementPort = vaultMeshSettlementPort;
        this.meshOnly = meshOnly;
        this.meshBucket = meshBucket == null || meshBucket.isBlank() ? "USERS" : meshBucket.trim();
        this.requiredSignatures = Math.max(1, requiredSignatures);
        this.fundingConfirmationTarget = Math.max(1, fundingConfirmationTarget);
        this.signerUrls = splitCsv(signerUrls);
        this.signerApiKeys = splitCsv(signerApiKeys);
        this.signerIds = splitCsv(signerIds);
        this.requireSignerIdentity = requireSignerIdentity;
        // Mesh-only cutover: never fall back to Core/mpc/local wallets for signing.
        this.localCoreSignerEnabled = meshOnly ? false : localCoreSignerEnabled;
        this.localCoreSignerId = firstNonBlank(localCoreSignerId, "bitcoin-core-wallet");
    }

    /** Test helper matching legacy constructor args (mesh disabled). */
    KfeQuorumPsbtSigningService(
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            int requiredSignatures,
            int fundingConfirmationTarget,
            String signerUrls,
            String signerApiKeys,
            String signerIds,
            boolean requireSignerIdentity,
            boolean localCoreSignerEnabled,
            String localCoreSignerId) {
        this(
                bitcoinCoreRpcClient,
                restTemplate,
                objectMapper,
                intent -> new VaultMeshReceipt(
                        intent == null ? null : intent.intentId(),
                        VaultMeshReceipt.Status.REJECTED,
                        "MESH_DISABLED",
                        null,
                        System.currentTimeMillis()),
                false,
                "USERS",
                requiredSignatures,
                fundingConfirmationTarget,
                signerUrls,
                signerApiKeys,
                signerIds,
                requireSignerIdentity,
                localCoreSignerEnabled,
                localCoreSignerId);
    }

    public OnchainFundingPreflight preflight(KfeOnchainPaymentGateway.OnchainPreflightCommand command) {
        BitcoinCoreRpcClient bitcoinCore = requireBitcoinCore();
        requireSignerCapacity();

        String changeType = meshOnly ? "bech32m" : "bech32";
        BitcoinCoreRpcClient.FundedPsbt fundedPsbt = bitcoinCore.createFundedPsbt(
                command.destinationAddress(),
                command.amountSats(),
                fundingConfirmationTarget,
                null,
                changeType);
        validateFundedPsbt(fundedPsbt, command.maxFeeSats());
        return new OnchainFundingPreflight(
                fundedPsbt.feeSats(),
                sha256(fundedPsbt.psbt()),
                configuredSignerCount());
    }

    public OnchainExecution execute(KfeOnchainPaymentGateway.OnchainPaymentCommand command) {
        BitcoinCoreRpcClient bitcoinCore = requireBitcoinCore();
        requireSignerCapacity();

        Integer confTarget = command.confirmationTarget() != null && command.confirmationTarget() > 0
                ? command.confirmationTarget()
                : fundingConfirmationTarget;
        Long feeRate = command.feeRateSatsPerVbyte() != null && command.feeRateSatsPerVbyte() > 0L
                ? command.feeRateSatsPerVbyte()
                : null;

        // Mesh Taproot path: prefer bech32m change so funded PSBTs stay P2TR-compatible.
        String changeType = meshOnly ? "bech32m" : "bech32";
        BitcoinCoreRpcClient.FundedPsbt fundedPsbt = bitcoinCore.createFundedPsbt(
                command.destinationAddress(),
                command.amountSats(),
                confTarget,
                feeRate,
                changeType);
        validateFundedPsbt(fundedPsbt, command.maxFeeSats());
        String fundedPsbtHash = sha256(fundedPsbt.psbt());

        log.info(
                "[KFE-PSBT] event=PSBT_CREATED userRef={} destinationRef={} walletNameRef={} amountSats={} feeRateSatVb={} confTarget={} fundedFeeSats={} meshOnly={}",
                LogSanitizer.fingerprint(String.valueOf(command.userId())),
                LogSanitizer.fingerprint(command.destinationAddress()),
                LogSanitizer.fingerprint(command.walletName()),
                command.amountSats(),
                feeRate,
                confTarget,
                fundedPsbt.feeSats(),
                meshOnly);

        if (meshOnly) {
            return executeMeshSigned(bitcoinCore, command, fundedPsbt, fundedPsbtHash);
        }

        List<String> partialPsbts = new ArrayList<>();
        partialPsbts.add(fundedPsbt.psbt());
        List<String> acceptedSigners = new ArrayList<>();

        // 1) Local Core wallet is a first-class production signer seat when enabled.
        if (localCoreSignerEnabled && acceptedSigners.size() < requiredSignatures) {
            try {
                String signed = bitcoinCore.walletProcessPsbt(fundedPsbt.psbt());
                if (signed != null && !signed.isBlank()) {
                    partialPsbts.add(signed);
                    acceptedSigners.add(localCoreSignerId);
                    log.info(
                            "[KFE-PSBT] event=PSBT_LOCAL_CORE_SIGNED userRef={} signerId={}",
                            LogSanitizer.fingerprint(String.valueOf(command.userId())),
                            localCoreSignerId);
                }
            } catch (Exception ex) {
                log.warn(
                        "[KFE-PSBT] event=PSBT_LOCAL_CORE_SIGNER_UNAVAILABLE userRef={} error={}",
                        LogSanitizer.fingerprint(String.valueOf(command.userId())),
                        ex.getMessage());
            }
        }

        // 2) Remote multiparty / HSM signers
        for (int index = 0; index < signerUrls.size(); index++) {
            if (acceptedSigners.size() >= requiredSignatures) {
                break;
            }
            String signerUrl = signerUrls.get(index);
            String apiKey = index < signerApiKeys.size() ? signerApiKeys.get(index) : null;
            String expectedSignerId = signerId(index);
            try {
                SignerSignature signature = requestSignature(
                        signerUrl,
                        apiKey,
                        expectedSignerId,
                        fundedPsbt.psbt(),
                        command);
                if (signature.signedPsbt() != null && !signature.signedPsbt().isBlank()) {
                    partialPsbts.add(signature.signedPsbt());
                    acceptedSigners.add(signature.signerId());
                }
            } catch (Exception ex) {
                log.warn(
                        "[KFE-PSBT] event=PSBT_SIGNER_UNAVAILABLE userRef={} signerRef={} error={}",
                        LogSanitizer.fingerprint(String.valueOf(command.userId())),
                        LogSanitizer.fingerprint(signerUrl),
                        ex.getMessage());
            }
        }

        if (acceptedSigners.size() < requiredSignatures) {
            throw new IllegalStateException(
                    "Quorum signing failed: " + acceptedSigners.size() + " of " + requiredSignatures
                            + " signers responded (configuredSeats=" + configuredSignerCount() + ").");
        }

        String combinedPsbt = bitcoinCore.combinePsbt(partialPsbts);
        return finalizeAndBroadcast(
                bitcoinCore,
                command,
                fundedPsbt,
                fundedPsbtHash,
                combinedPsbt,
                acceptedSigners);
    }

    private OnchainExecution executeMeshSigned(
            BitcoinCoreRpcClient bitcoinCore,
            KfeOnchainPaymentGateway.OnchainPaymentCommand command,
            BitcoinCoreRpcClient.FundedPsbt fundedPsbt,
            String fundedPsbtHash) {
        String intentId = firstNonBlank(command.idempotencyKey(), "onchain-" + System.currentTimeMillis());
        String sessionId = "btc-psbt-" + intentId;
        VaultMeshPsbtReceipt receipt = vaultMeshSettlementPort.signPsbt(new VaultMeshPsbtRequest(
                intentId,
                sessionId,
                meshBucket,
                command.destinationAddress(),
                command.amountSats(),
                fundedPsbt.psbt()));
        if (receipt.status() == VaultMeshReceipt.Status.FAIL_STOP) {
            throw new IllegalStateException(
                    "Vault mesh fail-stop during PSBT sign: " + receipt.reasonCode());
        }
        if (receipt.status() != VaultMeshReceipt.Status.ACCEPTED
                || receipt.signedPsbt() == null
                || receipt.signedPsbt().isBlank()) {
            throw new IllegalStateException(
                    "Vault mesh refused PSBT sign (mesh-only, no mpc fallback): "
                            + receipt.reasonCode());
        }
        log.info(
                "[KFE-PSBT] event=PSBT_MESH_SIGNED userRef={} intentRef={} proofRef={}",
                LogSanitizer.fingerprint(String.valueOf(command.userId())),
                LogSanitizer.fingerprint(intentId),
                LogSanitizer.fingerprint(receipt.signatureProof()));
        return finalizeAndBroadcast(
                bitcoinCore,
                command,
                fundedPsbt,
                fundedPsbtHash,
                receipt.signedPsbt(),
                List.of("vault-mesh"));
    }

    private OnchainExecution finalizeAndBroadcast(
            BitcoinCoreRpcClient bitcoinCore,
            KfeOnchainPaymentGateway.OnchainPaymentCommand command,
            BitcoinCoreRpcClient.FundedPsbt fundedPsbt,
            String fundedPsbtHash,
            String combinedPsbt,
            List<String> acceptedSigners) {
        String combinedPsbtHash = sha256(combinedPsbt);
        BitcoinCoreRpcClient.FinalizedPsbt finalizedPsbt = bitcoinCore.finalizePsbt(combinedPsbt);
        if (!finalizedPsbt.complete() || finalizedPsbt.hex() == null || finalizedPsbt.hex().isBlank()) {
            throw new IllegalStateException("Combined PSBT could not be finalized.");
        }
        String rawTxHash = sha256(finalizedPsbt.hex());

        String txid;
        try {
            txid = bitcoinCore.sendRawTransaction(finalizedPsbt.hex());
        } catch (RuntimeException broadcastFailure) {
            throw new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                    "Bitcoin Core broadcast result is ambiguous.",
                    combinedPsbtHash,
                    metadataJson(
                            fundedPsbtHash,
                            combinedPsbtHash,
                            rawTxHash,
                            acceptedSigners,
                            fundedPsbt.feeSats(),
                            null,
                            "UNKNOWN"),
                    broadcastFailure);
        }
        if (txid == null || txid.isBlank()) {
            throw new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                    "Bitcoin Core broadcast did not return a txid.",
                    combinedPsbtHash,
                    metadataJson(
                            fundedPsbtHash,
                            combinedPsbtHash,
                            rawTxHash,
                            acceptedSigners,
                            fundedPsbt.feeSats(),
                            null,
                            "UNKNOWN"),
                    null);
        }

        log.info(
                "[KFE-PSBT] event=PSBT_BROADCAST userRef={} txidRef={} signedBy={} destinationRef={} meshOnly={}",
                LogSanitizer.fingerprint(String.valueOf(command.userId())),
                LogSanitizer.fingerprint(txid),
                acceptedSigners.size(),
                LogSanitizer.fingerprint(command.destinationAddress()),
                meshOnly);

        return new OnchainExecution(
                txid,
                fundedPsbt.feeSats(),
                fundedPsbtHash,
                combinedPsbtHash,
                rawTxHash,
                acceptedSigners,
                metadataJson(
                        fundedPsbtHash,
                        combinedPsbtHash,
                        rawTxHash,
                        acceptedSigners,
                        fundedPsbt.feeSats(),
                        txid,
                        "MEMPOOL"));
    }

    private SignerSignature requestSignature(
            String signerUrl,
            String apiKey,
            String expectedSignerId,
            String psbt,
            KfeOnchainPaymentGateway.OnchainPaymentCommand command) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("psbt", psbt);
        payload.put("userId", command.userId());
        payload.put("walletId", command.walletId());
        payload.put("walletName", command.walletName());
        payload.put("destinationAddress", command.destinationAddress());
        payload.put("amountSats", command.amountSats());
        payload.put("idempotencyKey", command.idempotencyKey());
        payload.put("authorizationProof", command.authorizationProof());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }

        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(signerUrl, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Signer returned HTTP " + response.getStatusCode());
        }

        JsonNode body = objectMapper.readTree(response.getBody());
        String actualSignerId = text(body, "signerId", "signer_id", "signer");
        validateSignerIdentity(expectedSignerId, actualSignerId, signerUrl);

        String signedPsbt = text(body, "signedPsbt", "psbt");
        if (signedPsbt != null && !signedPsbt.isBlank()) {
            return new SignerSignature(expectedSignerId, signedPsbt);
        }
        throw new IllegalStateException("Signer did not return a signed PSBT.");
    }

    private BitcoinCoreRpcClient requireBitcoinCore() {
        BitcoinCoreRpcClient bitcoinCore = bitcoinCoreRpcClient.getIfAvailable();
        if (bitcoinCore == null) {
            throw new IllegalStateException("Bitcoin Core RPC is required for on-chain payments.");
        }
        return bitcoinCore;
    }

    private void requireSignerCapacity() {
        if (meshOnly) {
            return;
        }
        int seats = configuredSignerCount();
        if (seats == 0) {
            throw new IllegalStateException(
                    "No quorum PSBT signer endpoints are configured. "
                            + "Set quorum.psbt.signer-urls and/or enable quorum.psbt.local-core-signer-enabled.");
        }
        if (seats < requiredSignatures) {
            throw new IllegalStateException(
                    "Quorum signing requires " + requiredSignatures + " signers but only "
                            + seats + " seats are configured.");
        }
    }

    private int configuredSignerCount() {
        if (meshOnly) {
            return 1;
        }
        int remote = signerUrls.size();
        if (localCoreSignerEnabled && bitcoinCoreRpcClient.getIfAvailable() != null) {
            return remote + 1;
        }
        return remote;
    }

    private void validateFundedPsbt(BitcoinCoreRpcClient.FundedPsbt fundedPsbt, long maxFeeSats) {
        if (fundedPsbt.psbt() == null || fundedPsbt.psbt().isBlank()) {
            throw new IllegalStateException("Bitcoin Core did not return a PSBT.");
        }
        if (maxFeeSats < 0L) {
            throw new IllegalArgumentException("On-chain fee limit must be non-negative.");
        }
        if (fundedPsbt.feeSats() > maxFeeSats) {
            throw new IllegalArgumentException(
                    "Funded PSBT fee exceeds configured on-chain fee cap. actualFeeSats="
                            + fundedPsbt.feeSats()
                            + " capFeeSats="
                            + maxFeeSats
                            + " (quote too low for coin selection / vsize — re-quote or raise network fee).");
        }
    }

    private void validateSignerIdentity(String expectedSignerId, String actualSignerId, String signerUrl) {
        if (actualSignerId == null || actualSignerId.isBlank()) {
            if (requireSignerIdentity) {
                throw new IllegalStateException("Signer " + expectedSignerId + " did not return signerId.");
            }
            return;
        }
        if (!expectedSignerId.equals(actualSignerId.trim())) {
            throw new IllegalStateException(
                    "Signer identity mismatch for " + signerUrl + ": expected " + expectedSignerId + ".");
        }
    }

    private String signerId(int index) {
        return index < signerIds.size() ? signerIds.get(index) : "signer-" + (index + 1);
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String metadataJson(
            String fundedPsbtHash,
            String combinedPsbtHash,
            String rawTxHash,
            List<String> acceptedSigners,
            long feeSats,
            String txid,
            String status) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", meshOnly ? "BITCOIN_CORE_VAULT_MESH" : "BITCOIN_CORE_QUORUM");
        metadata.put("status", status);
        metadata.put("fundedPsbtHash", fundedPsbtHash);
        metadata.put("combinedPsbtHash", combinedPsbtHash);
        metadata.put("rawTxHash", rawTxHash);
        metadata.put("acceptedSigners", acceptedSigners);
        metadata.put("acceptedSignerCount", acceptedSigners.size());
        metadata.put("requiredSignatures", meshOnly ? 1 : requiredSignatures);
        metadata.put("localCoreSignerEnabled", localCoreSignerEnabled);
        metadata.put("meshOnly", meshOnly);
        metadata.put("feeSats", feeSats);
        metadata.put("txid", txid);
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            return metadata.toString();
        }
    }

    private String sha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash PSBT metadata.", exception);
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return fallback;
    }

    public record OnchainFundingPreflight(
            long feeSats,
            String psbtHash,
            int configuredSignerCount) {
    }

    public record OnchainExecution(
            String txid,
            long feeSats,
            String fundedPsbtHash,
            String combinedPsbtHash,
            String rawTransactionHash,
            List<String> acceptedSigners,
            String metadataJson) {
    }

    private record SignerSignature(
            String signerId,
            String signedPsbt) {
    }
}
