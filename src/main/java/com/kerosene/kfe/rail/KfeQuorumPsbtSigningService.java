package com.kerosene.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.TreeSet;

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
    private final SignerFingerprintAllowlist signerFingerprintAllowlist;

    @Autowired
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
            @Value("${quorum.psbt.local-core-signer-id:bitcoin-core-wallet}") String localCoreSignerId,
            @Value("${quorum.psbt.signer-tls-fingerprints:}") String signerTlsFingerprints) {
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
        this.signerFingerprintAllowlist = new SignerFingerprintAllowlist(signerTlsFingerprints);
        emitStartupSecurityWarnings();
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
                        Instant.now()),
                false,
                "USERS",
                requiredSignatures,
                fundingConfirmationTarget,
                signerUrls,
                signerApiKeys,
                signerIds,
                requireSignerIdentity,
                localCoreSignerEnabled,
                localCoreSignerId,
                "");
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
        return broadcast(prepare(command));
    }

    public KfeOnchainPaymentGateway.PreparedOnchainPayment prepare(
            KfeOnchainPaymentGateway.OnchainPaymentCommand command) {
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
                changeType,
                true);
        try {
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
        Set<String> acceptedCryptographicSigners = new LinkedHashSet<>();

        // 1) Local Core wallet is a first-class production signer seat when enabled.
        if (localCoreSignerEnabled && acceptedCryptographicSigners.size() < requiredSignatures) {
            try {
                String signed = bitcoinCore.walletProcessPsbt(fundedPsbt.psbt());
                if (signed != null && !signed.isBlank()) {
                    // Verify this partial PSBT did not alter transaction structure.
                    Set<String> contribution = verifyPartialPsbtStructure(
                            bitcoinCore, fundedPsbt.psbt(), signed, localCoreSignerId);
                    contribution.removeAll(acceptedCryptographicSigners);
                    if (contribution.isEmpty()) {
                        throw new IllegalStateException(
                                "Local Core signer did not add a distinct cryptographic signature.");
                    }
                    partialPsbts.add(signed);
                    acceptedCryptographicSigners.addAll(contribution);
                    contribution.forEach(key -> acceptedSigners.add(signerLabel(localCoreSignerId, key)));
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
            if (acceptedCryptographicSigners.size() >= requiredSignatures) {
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
                    // Verify this partial PSBT did not alter transaction structure.
                    Set<String> contribution = verifyPartialPsbtStructure(
                            bitcoinCore, fundedPsbt.psbt(), signature.signedPsbt(), expectedSignerId);
                    contribution.removeAll(acceptedCryptographicSigners);
                    if (contribution.isEmpty()) {
                        throw new IllegalStateException(
                                "Signer did not add a distinct cryptographic signature.");
                    }
                    partialPsbts.add(signature.signedPsbt());
                    acceptedCryptographicSigners.addAll(contribution);
                    contribution.forEach(key -> acceptedSigners.add(signerLabel(signature.signerId(), key)));
                }
            } catch (Exception ex) {
                log.warn(
                        "[KFE-PSBT] event=PSBT_SIGNER_UNAVAILABLE userRef={} signerRef={} error={}",
                        LogSanitizer.fingerprint(String.valueOf(command.userId())),
                        LogSanitizer.fingerprint(signerUrl),
                        ex.getMessage());
            }
        }

        if (acceptedCryptographicSigners.size() < requiredSignatures) {
            throw new IllegalStateException(
                    "Quorum signing failed: " + acceptedCryptographicSigners.size() + " of "
                            + requiredSignatures + " distinct cryptographic signers contributed "
                            + "(configuredSeats=" + configuredSignerCount() + ").");
        }

        String combinedPsbt = bitcoinCore.combinePsbt(partialPsbts);
        Set<String> combinedCryptographicSigners = collectPartialSignerKeys(bitcoinCore.decodePsbt(combinedPsbt));
        if (combinedCryptographicSigners.size() < requiredSignatures
                || !combinedCryptographicSigners.containsAll(acceptedCryptographicSigners)) {
            throw new IllegalStateException(
                    "Combined PSBT does not preserve the required distinct cryptographic signatures.");
        }
        // Verify combined PSBT before finalization.
        String intentId = firstNonBlank(
                command.idempotencyKey(), "onchain-quorum-" + System.currentTimeMillis());
        verifySignedPsbtIntegrity(
                bitcoinCore,
                fundedPsbt.psbt(),
                combinedPsbt,
                command.destinationAddress(),
                command.amountSats(),
                intentId);
            return finalizePrepared(
                    bitcoinCore,
                    command,
                    fundedPsbt,
                    fundedPsbtHash,
                    combinedPsbt,
                    acceptedSigners,
                    intentId);
        } catch (RuntimeException exception) {
            bitcoinCore.unlockPsbtInputsBestEffort(fundedPsbt.psbt());
            throw exception;
        }
    }

    private KfeOnchainPaymentGateway.PreparedOnchainPayment executeMeshSigned(
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

        // [P0 ITEM 2] Verify signed PSBT pays the same outputs as the original funded PSBT.
        verifySignedPsbtIntegrity(
                bitcoinCore,
                fundedPsbt.psbt(),
                receipt.signedPsbt(),
                command.destinationAddress(),
                command.amountSats(),
                intentId);

        log.info(
                "[KFE-PSBT] event=PSBT_MESH_SIGNED userRef={} intentRef={} proofRef={}",
                LogSanitizer.fingerprint(String.valueOf(command.userId())),
                LogSanitizer.fingerprint(intentId),
                LogSanitizer.fingerprint(receipt.signatureProof()));
        return finalizePrepared(
                bitcoinCore,
                command,
                fundedPsbt,
                fundedPsbtHash,
                receipt.signedPsbt(),
                List.of("vault-mesh"),
                intentId);
    }

    private KfeOnchainPaymentGateway.PreparedOnchainPayment finalizePrepared(
            BitcoinCoreRpcClient bitcoinCore,
            KfeOnchainPaymentGateway.OnchainPaymentCommand command,
            BitcoinCoreRpcClient.FundedPsbt fundedPsbt,
            String fundedPsbtHash,
            String combinedPsbt,
            List<String> acceptedSigners,
            String intentId) {
        String combinedPsbtHash = sha256(combinedPsbt);
        BitcoinCoreRpcClient.FinalizedPsbt finalizedPsbt = bitcoinCore.finalizePsbt(combinedPsbt);
        if (!finalizedPsbt.complete() || finalizedPsbt.hex() == null || finalizedPsbt.hex().isBlank()) {
            throw new IllegalStateException("Combined PSBT could not be finalized.");
        }
        String rawTxHash = sha256(finalizedPsbt.hex());

        // [P0 ITEM 2] Re-verify after finalization: decode raw transaction and compare
        // against original funded PSBT transaction structure.
        verifyFinalizedTransactionMatchesFundedPsbt(
                bitcoinCore, fundedPsbt, finalizedPsbt.hex(),
                command.destinationAddress(), command.amountSats(), intentId);

        JsonNode decodedRaw = bitcoinCore.decodeRawTransaction(finalizedPsbt.hex());
        String expectedTxid = text(decodedRaw, "txid");
        if (expectedTxid == null || !expectedTxid.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException("Bitcoin Core did not derive a valid txid for the finalized transaction.");
        }

        return new KfeOnchainPaymentGateway.PreparedOnchainPayment(
                finalizedPsbt.hex(),
                expectedTxid.toLowerCase(Locale.ROOT),
                fundedPsbt.feeSats(),
                fundedPsbtHash,
                combinedPsbtHash,
                rawTxHash,
                acceptedSigners,
                intentId,
                metadataJson(
                        fundedPsbtHash,
                        combinedPsbtHash,
                        rawTxHash,
                        acceptedSigners,
                        fundedPsbt.feeSats(),
                        expectedTxid,
                        "PREPARED",
                        intentId));
    }

    public OnchainExecution broadcast(KfeOnchainPaymentGateway.PreparedOnchainPayment prepared) {
        if (prepared == null
                || prepared.rawTransaction() == null
                || prepared.rawTransaction().isBlank()
                || prepared.expectedTxid() == null
                || !prepared.expectedTxid().matches("(?i)[0-9a-f]{64}")
                || prepared.rawTransactionHash() == null
                || !prepared.rawTransactionHash().matches("(?i)[0-9a-f]{64}")
                || prepared.feeSats() < 0L
                || prepared.acceptedSigners().isEmpty()) {
            throw new IllegalArgumentException("A valid prepared raw transaction and expected txid are required.");
        }
        BitcoinCoreRpcClient bitcoinCore = requireBitcoinCore();
        String rawTransaction = prepared.rawTransaction().trim();
        String expectedTxid = prepared.expectedTxid().trim().toLowerCase(Locale.ROOT);
        if (!MessageDigest.isEqual(
                sha256(rawTransaction).getBytes(StandardCharsets.US_ASCII),
                prepared.rawTransactionHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Prepared raw transaction hash mismatch.");
        }
        JsonNode decoded = bitcoinCore.decodeRawTransaction(rawTransaction);
        String decodedTxid = text(decoded, "txid");
        if (decodedTxid == null || !expectedTxid.equals(decodedTxid.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Prepared transaction does not decode to its expected txid.");
        }

        if (bitcoinCore.findTransactionConfirmations(expectedTxid).isPresent()) {
            return completedExecution(prepared, expectedTxid, "KNOWN");
        }

        try {
            bitcoinCore.requireMempoolAccept(rawTransaction);
        } catch (RuntimeException policyRejection) {
            if (bitcoinCore.findTransactionConfirmations(expectedTxid).isPresent()) {
                return completedExecution(prepared, expectedTxid, "KNOWN");
            }
            bitcoinCore.unlockRawTransactionInputsBestEffort(rawTransaction);
            throw new IllegalArgumentException(
                    "Prepared transaction was rejected by Bitcoin Core mempool policy.",
                    policyRejection);
        }

        try {
            String returnedTxid = bitcoinCore.sendRawTransaction(rawTransaction);
            if (returnedTxid == null || !expectedTxid.equals(returnedTxid.trim().toLowerCase(Locale.ROOT))) {
                throw ambiguousBroadcast(
                        prepared,
                        "Bitcoin Core broadcast returned an unexpected or empty txid.",
                        null);
            }
        } catch (KfeOnchainPaymentGateway.ProviderExecutionAmbiguous ambiguous) {
            throw ambiguous;
        } catch (RuntimeException broadcastFailure) {
            if (!bitcoinCore.findTransactionConfirmations(expectedTxid).isPresent()) {
                throw ambiguousBroadcast(
                        prepared,
                        "Bitcoin Core broadcast result is ambiguous.",
                        broadcastFailure);
            }
        }
        return completedExecution(prepared, expectedTxid, "MEMPOOL");
    }

    public void release(KfeOnchainPaymentGateway.PreparedOnchainPayment prepared) {
        if (prepared != null && prepared.rawTransaction() != null && !prepared.rawTransaction().isBlank()) {
            requireBitcoinCore().unlockRawTransactionInputsBestEffort(prepared.rawTransaction());
        }
    }

    private KfeOnchainPaymentGateway.ProviderExecutionAmbiguous ambiguousBroadcast(
            KfeOnchainPaymentGateway.PreparedOnchainPayment prepared,
            String message,
            Throwable cause) {
        return new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                message,
                prepared.expectedTxid(),
                metadataJson(
                        prepared.fundedPsbtHash(),
                        prepared.combinedPsbtHash(),
                        prepared.rawTransactionHash(),
                        prepared.acceptedSigners(),
                        prepared.feeSats(),
                        prepared.expectedTxid(),
                        "UNKNOWN",
                        prepared.intentId()),
                cause);
    }

    private OnchainExecution completedExecution(
            KfeOnchainPaymentGateway.PreparedOnchainPayment prepared,
            String txid,
            String status) {

        log.info(
                "[KFE-PSBT] event=PSBT_BROADCAST userRef={} txidRef={} signedBy={} destinationRef={} meshOnly={}",
                LogSanitizer.fingerprint(prepared.intentId()),
                LogSanitizer.fingerprint(txid),
                prepared.acceptedSigners().size(),
                "prepared",
                meshOnly);

        return new OnchainExecution(
                txid,
                prepared.feeSats(),
                prepared.fundedPsbtHash(),
                prepared.combinedPsbtHash(),
                prepared.rawTransactionHash(),
                prepared.acceptedSigners(),
                metadataJson(
                        prepared.fundedPsbtHash(),
                        prepared.combinedPsbtHash(),
                        prepared.rawTransactionHash(),
                        prepared.acceptedSigners(),
                        prepared.feeSats(),
                        txid,
                        status,
                        prepared.intentId()));
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

    /**
     * Validates that the signer returning a partial PSBT is who it claims to be.
     *
     * <p><strong>P0 SECURITY WARNING (ITEM 5):</strong> This method relies on self-claimed
     * {@code signerId} from the HTTP JSON response body. A compromised signer can return
     * any signerId value, bypassing identity validation entirely. Production deployments
     * MUST use the vault mesh path ({@code kfe.vaultmesh.mesh-only=true}) which enforces
     * mTLS/SPIFFE certificate-based identity. The quorum HTTP signer path
     * ({@code quorum.psbt.signer-urls}) is a legacy path and SHOULD NOT be used in
     * production without certificate fingerprint verification via
     * {@code quorum.psbt.signer-tls-fingerprints}.
     *
     * @deprecated Use mTLS certificate-based identity via vault mesh or
     *             {@code quorum.psbt.signer-tls-fingerprints} allowlist. The self-claimed
     *             JSON signerId is not cryptographically verifiable.
     */
    @Deprecated
    private void validateSignerIdentity(String expectedSignerId, String actualSignerId, String signerUrl) {
        if (actualSignerId == null || actualSignerId.isBlank()) {
            if (requireSignerIdentity) {
                log.error(
                        "[KFE-PSBT] event=SIGNER_ID_MISSING urlRef={} expectedRef={} "
                                + "WARNING: self-claimed identity missing from HTTP response.",
                        LogSanitizer.fingerprint(signerUrl),
                        LogSanitizer.fingerprint(expectedSignerId));
                throw new IllegalStateException("Signer " + expectedSignerId + " did not return signerId.");
            }
            return;
        }
        if (!expectedSignerId.equals(actualSignerId.trim())) {
            log.error(
                    "[KFE-PSBT] event=SIGNER_ID_MISMATCH urlRef={} expectedRef={} actualRef={} "
                            + "WARNING: self-claimed identity differs from expected — "
                            + "compromised signer or configuration error.",
                    LogSanitizer.fingerprint(signerUrl),
                    LogSanitizer.fingerprint(expectedSignerId),
                    LogSanitizer.fingerprint(actualSignerId));
            throw new IllegalStateException(
                    "Signer identity mismatch for " + signerUrl + ": expected " + expectedSignerId + ".");
        }
    }

    /**
     * Emits CRITICAL-level startup warnings when the legacy HTTP signer path is configured
     * without cryptographic identity verification (mTLS certificate fingerprints).
     */
    private void emitStartupSecurityWarnings() {
        if (meshOnly) {
            return;
        }
        if (!signerUrls.isEmpty()) {
            if (!signerFingerprintAllowlist.isConfigured()) {
                log.error(
                        "[KFE-PSBT] P0_SECURITY: quorum.psbt.signer-urls configured without "
                                + "quorum.psbt.signer-tls-fingerprints. Signer identity relies on "
                                + "self-claimed JSON signerId — a compromised signer can impersonate "
                                + "any signerId. Set quorum.psbt.signer-tls-fingerprints or migrate "
                                + "to kfe.vaultmesh.mesh-only=true.");
            }
            if (!requireSignerIdentity) {
                log.error(
                        "[KFE-PSBT] P0_SECURITY: quorum.psbt.require-signer-identity=false disables "
                                + "all signer identity checks for the legacy HTTP path. This allows "
                                + "any endpoint to contribute partial PSBTs without identity validation.");
            }
        }
    }

    // ──── ITEM 2: PSBT Integrity Verification ────

    /**
     * Verifies that a signed/combined PSBT preserves the transaction structure of the
     * original funded PSBT. Rejects tampered PSBTs before finalization.
     *
     * <p>Checks: inputs (txid+vout+sequence), outputs (address+value), locktime, version,
     * fee consistency. A malicious threshold coalition cannot redirect payment to a
     * different address or inflate/deflate amounts.
     */
    private void verifySignedPsbtIntegrity(
            BitcoinCoreRpcClient bitcoinCore,
            String originalPsbt,
            String signedPsbt,
            String expectedDestination,
            long expectedAmountSats,
            String intentId) {
        JsonNode decodedOriginal = bitcoinCore.decodePsbt(originalPsbt);
        JsonNode decodedSigned = bitcoinCore.decodePsbt(signedPsbt);
        if (decodedOriginal == null || decodedOriginal.isNull() || decodedOriginal.isMissingNode()) {
            throw new IllegalStateException("Unable to decode original funded PSBT for integrity check.");
        }
        if (decodedSigned == null || decodedSigned.isNull() || decodedSigned.isMissingNode()) {
            throw new IllegalStateException("Unable to decode signed PSBT for integrity check.");
        }

        JsonNode origTx = decodedOriginal.path("tx");
        JsonNode signedTx = decodedSigned.path("tx");
        if (origTx.isMissingNode() || origTx.isNull()) {
            throw new IllegalStateException("Original PSBT missing transaction payload.");
        }
        if (signedTx.isMissingNode() || signedTx.isNull()) {
            throw new IllegalStateException("Signed PSBT missing transaction payload.");
        }

        int origVersion = origTx.path("version").asInt(-1);
        int signedVersion = signedTx.path("version").asInt(-1);
        if (origVersion != signedVersion) {
            throw new IllegalStateException(
                    "PSBT integrity violation: transaction version changed from "
                            + origVersion + " to " + signedVersion + ". intentId=" + intentId);
        }

        long origLocktime = origTx.path("locktime").asLong(-1L);
        long signedLocktime = signedTx.path("locktime").asLong(-1L);
        if (origLocktime != signedLocktime) {
            throw new IllegalStateException(
                    "PSBT integrity violation: locktime changed from "
                            + origLocktime + " to " + signedLocktime + ". intentId=" + intentId);
        }

        // Compare inputs: must be identical set (txid+vout+sequence).
        Set<String> originalInputs = collectInputKeys(origTx.path("vin"));
        Set<String> signedInputs = collectInputKeys(signedTx.path("vin"));
        if (originalInputs.size() != signedInputs.size()
                || !originalInputs.equals(signedInputs)) {
            throw new IllegalStateException(
                    "PSBT integrity violation: input set changed. "
                            + "originalCount=" + originalInputs.size()
                            + " signedCount=" + signedInputs.size()
                            + " intentId=" + intentId);
        }

        // Compare outputs: must be identical set (scriptPubKey+value). No additions/removals.
        Set<String> originalOutputs = collectOutputKeys(origTx.path("vout"));
        Set<String> signedOutputs = collectOutputKeys(signedTx.path("vout"));
        if (originalOutputs.size() != signedOutputs.size()
                || !originalOutputs.equals(signedOutputs)) {
            throw new IllegalStateException(
                    "PSBT integrity violation: output set changed. "
                            + "originalCount=" + originalOutputs.size()
                            + " signedCount=" + signedOutputs.size()
                            + " intentId=" + intentId);
        }

        // Verify destination payment exists for the expected amount.
        boolean foundPayment = false;
        JsonNode signedVout = signedTx.path("vout");
        for (JsonNode output : signedVout) {
            String address = extractOutputAddress(output);
            long valueSats = btcFieldToSats(output.path("value"));
            if (expectedDestination.equalsIgnoreCase(address != null ? address.trim() : "")
                    && valueSats == expectedAmountSats) {
                foundPayment = true;
                break;
            }
        }
        if (!foundPayment) {
            throw new IllegalStateException(
                    "PSBT integrity violation: signed PSBT does not pay "
                            + expectedDestination + " " + expectedAmountSats + " sats."
                            + " intentId=" + intentId);
        }

        // Fee consistency.
        long signedFee = computeFeeFromDecodedPsbt(decodedSigned);
        long originalFee = computeFeeFromDecodedPsbt(decodedOriginal);
        if (signedFee != originalFee) {
            throw new IllegalStateException(
                    "PSBT integrity violation: fee changed from "
                            + originalFee + " to " + signedFee + " sats. intentId=" + intentId);
        }
    }

    /**
     * Lightweight structural check for a partial PSBT. Ensures the partial signer did
     * not alter the transaction inputs before contributing its signature. Counts
     * distinct cryptographic signers from PSBT partial_signatures.
     */
    private Set<String> verifyPartialPsbtStructure(
            BitcoinCoreRpcClient bitcoinCore,
            String originalPsbt,
            String partialPsbt,
            String signerId) {
        JsonNode decodedOriginal = bitcoinCore.decodePsbt(originalPsbt);
        JsonNode decodedPartial = bitcoinCore.decodePsbt(partialPsbt);
        if (decodedOriginal == null || decodedOriginal.isNull()
                || decodedPartial == null || decodedPartial.isNull()) {
            throw new IllegalStateException(
                    "Unable to decode PSBT for signer " + signerId + " partial integrity check.");
        }
        JsonNode origTx = decodedOriginal.path("tx");
        JsonNode partialTx = decodedPartial.path("tx");
        if (origTx.isMissingNode() || partialTx.isMissingNode()) {
            throw new IllegalStateException(
                    "Signer " + signerId + " returned PSBT without transaction payload.");
        }

        Set<String> originalInputs = collectInputKeys(origTx.path("vin"));
        Set<String> partialInputs = collectInputKeys(partialTx.path("vin"));
        if (!originalInputs.equals(partialInputs)) {
            throw new IllegalStateException(
                    "Signer " + signerId + " altered PSBT inputs. Rejecting partial signature.");
        }

        Set<String> originalSigners = collectPartialSignerKeys(decodedOriginal);
        Set<String> contributedSigners = new LinkedHashSet<>(collectPartialSignerKeys(decodedPartial));
        contributedSigners.removeAll(originalSigners);
        if (contributedSigners.isEmpty()) {
            throw new IllegalStateException(
                    "Signer " + signerId + " returned a PSBT without a new cryptographic signature.");
        }
        return contributedSigners;
    }

    /**
     * After finalization, decodes the raw transaction and verifies it matches the
     * original funded PSBT transaction structure. Last line of defense before broadcast.
     */
    private void verifyFinalizedTransactionMatchesFundedPsbt(
            BitcoinCoreRpcClient bitcoinCore,
            BitcoinCoreRpcClient.FundedPsbt fundedPsbt,
            String rawTxHex,
            String expectedDestination,
            long expectedAmountSats,
            String intentId) {
        JsonNode decodedOriginal = bitcoinCore.decodePsbt(fundedPsbt.psbt());
        if (decodedOriginal == null || decodedOriginal.isNull()) {
            throw new IllegalStateException("Unable to decode original PSBT for post-finalize check.");
        }
        JsonNode origTx = decodedOriginal.path("tx");

        JsonNode decodedRaw;
        try {
            decodedRaw = bitcoinCore.decodeRawTransaction(rawTxHex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Unable to decode finalized raw transaction for integrity check. intentId=" + intentId, ex);
        }
        if (decodedRaw == null || decodedRaw.isNull() || decodedRaw.isMissingNode()) {
            throw new IllegalStateException(
                    "Post-finalize integrity check could not decode raw transaction. intentId=" + intentId);
        }

        int origVersion = origTx.path("version").asInt(-1);
        int finalVersion = decodedRaw.path("version").asInt(-1);
        if (origVersion != finalVersion) {
            throw new IllegalStateException(
                    "Post-finalize violation: version " + origVersion + " -> " + finalVersion
                            + ". intentId=" + intentId);
        }

        long origLocktime = origTx.path("locktime").asLong(-1L);
        long finalLocktime = decodedRaw.path("locktime").asLong(-1L);
        if (origLocktime != finalLocktime) {
            throw new IllegalStateException(
                    "Post-finalize violation: locktime " + origLocktime + " -> " + finalLocktime
                            + ". intentId=" + intentId);
        }

        Set<String> origInputs = collectInputKeys(origTx.path("vin"));
        Set<String> finalInputs = collectInputKeys(decodedRaw.path("vin"));
        if (!origInputs.equals(finalInputs)) {
            throw new IllegalStateException(
                    "Post-finalize violation: input set changed. intentId=" + intentId);
        }

        Set<String> origOutputs = collectOutputKeys(origTx.path("vout"));
        Set<String> finalOutputs = collectOutputKeys(decodedRaw.path("vout"));
        if (!origOutputs.equals(finalOutputs)) {
            throw new IllegalStateException(
                    "Post-finalize violation: output set changed. intentId=" + intentId);
        }

        boolean foundPayment = false;
        JsonNode finalVout = decodedRaw.path("vout");
        for (JsonNode output : finalVout) {
            String address = extractOutputAddress(output);
            long valueSats = btcFieldToSats(output.path("value"));
            if (expectedDestination.equalsIgnoreCase(address != null ? address.trim() : "")
                    && valueSats == expectedAmountSats) {
                foundPayment = true;
                break;
            }
        }
        if (!foundPayment) {
            throw new IllegalStateException(
                    "Post-finalize violation: finalized transaction does not pay "
                            + expectedDestination + " " + expectedAmountSats + " sats."
                            + " intentId=" + intentId);
        }
    }

    private static Set<String> collectInputKeys(JsonNode vin) {
        if (vin == null || !vin.isArray()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode input : vin) {
            String txid = textField(input, "txid");
            int vout = input.path("vout").asInt(-1);
            long sequence = input.path("sequence").asLong(-1L);
            if (txid != null && !txid.isBlank() && vout >= 0) {
                keys.add(txid.trim().toLowerCase(Locale.ROOT) + ":" + vout + ":" + sequence);
            }
        }
        return keys;
    }

    private static Set<String> collectOutputKeys(JsonNode vout) {
        if (vout == null || !vout.isArray()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode output : vout) {
            long valueSats = btcFieldToSats(output.path("value"));
            String address = extractOutputAddress(output);
            String addrKey = address != null
                    ? address.trim().toLowerCase(Locale.ROOT)
                    : "unknown";
            keys.add(addrKey + ":" + valueSats);
        }
        return keys;
    }

    /**
     * Counts distinct signers by inspecting partial_signatures in the decoded PSBT.
     * This counts cryptographic signatures, not self-claimed HTTP identities.
     */
    static int countDistinctPartialSigners(JsonNode decodedPsbt) {
        return collectPartialSignerKeys(decodedPsbt).size();
    }

    static Set<String> collectPartialSignerKeys(JsonNode decodedPsbt) {
        if (decodedPsbt == null) {
            return Set.of();
        }
        JsonNode inputs = decodedPsbt.path("inputs");
        if (!inputs.isArray()) {
            return Set.of();
        }
        Set<String> uniquePubkeys = new TreeSet<>();
        for (JsonNode inputNode : inputs) {
            JsonNode partialSigs = inputNode.path("partial_signatures");
            if (partialSigs.isObject()) {
                partialSigs.fieldNames().forEachRemaining(key -> addNormalizedSignerKey(uniquePubkeys, key));
            }
            JsonNode taprootScriptSigs = inputNode.path("taproot_script_path_sigs");
            if (taprootScriptSigs.isObject()) {
                taprootScriptSigs.fieldNames()
                        .forEachRemaining(key -> addNormalizedSignerKey(uniquePubkeys, key));
            }
        }
        return Set.copyOf(uniquePubkeys);
    }

    private static void addNormalizedSignerKey(Set<String> target, String rawKey) {
        if (rawKey == null) {
            return;
        }
        String candidate = rawKey.trim().toLowerCase(Locale.ROOT).split("[,/:]", 2)[0];
        if (candidate.matches("[0-9a-f]{64}") || candidate.matches("0[23][0-9a-f]{64}")) {
            target.add(candidate);
        }
    }

    /**
     * Computes the fee from a decoded PSBT by summing witness_utxo amounts (inputs)
     * and subtracting output values.
     */
    private static long computeFeeFromDecodedPsbt(JsonNode decodedPsbt) {
        if (decodedPsbt == null) {
            return 0L;
        }
        long totalInput = 0L;
        JsonNode inputs = decodedPsbt.path("inputs");
        if (inputs.isArray()) {
            for (JsonNode input : inputs) {
                JsonNode witnessUtxo = input.path("witness_utxo");
                if (!witnessUtxo.isMissingNode() && !witnessUtxo.isNull()) {
                    totalInput += btcFieldToSats(witnessUtxo.path("amount"));
                }
            }
        }
        long totalOutput = 0L;
        JsonNode tx = decodedPsbt.path("tx");
        JsonNode vout = tx.path("vout");
        if (vout.isArray()) {
            for (JsonNode output : vout) {
                totalOutput += btcFieldToSats(output.path("value"));
            }
        }
        return totalInput - totalOutput;
    }

    private static String extractOutputAddress(JsonNode output) {
        if (output == null) {
            return null;
        }
        JsonNode spk = output.path("scriptPubKey");
        String address = textField(spk, "address");
        if (address != null) {
            return address;
        }
        JsonNode addresses = spk.path("addresses");
        if (addresses.isArray() && !addresses.isEmpty()) {
            return addresses.get(0).asText(null);
        }
        return null;
    }

    private static long btcFieldToSats(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull() || valueNode.isMissingNode()) {
            return 0L;
        }
        if (valueNode.isIntegralNumber()) {
            long v = valueNode.asLong();
            return v >= 0L && v < 21_000_000L * 100_000_000L ? v : 0L;
        }
        if (!valueNode.isNumber()) {
            return 0L;
        }
        BigDecimal btc = valueNode.decimalValue();
        if (btc.signum() <= 0) {
            return 0L;
        }
        return btc.multiply(new BigDecimal("100000000"))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value == null || value.isMissingNode() || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text != null && !text.isBlank() ? text : null;
    }

    // ──── ITEM 5: Signer Certificate Allowlist ────

    /**
     * Maps signer identities to certificate fingerprints for mTLS-based verification.
     * Replaces self-claimed JSON signerId with cryptographically-bound identity.
     *
     * <p>Fingerprint format: colon-separated SHA-256 hex (e.g. AB:CD:...:EF).
     * Empty allowlist means certificate checks are skipped (lab mode).
     */
    static class SignerFingerprintAllowlist {
        private final Map<String, List<String>> signerIdToFingerprints;

        SignerFingerprintAllowlist(String rawCsv) {
            this.signerIdToFingerprints = parseAllowlist(rawCsv);
            // Validate that every signerId is unique and no fingerprint is assigned
            // to more than one signer.
            Set<String> allFingerprints = new LinkedHashSet<>();
            for (var entry : this.signerIdToFingerprints.entrySet()) {
                for (String fp : entry.getValue()) {
                    if (!allFingerprints.add(fp.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException(
                                "quorum.psbt.signer-tls-fingerprints: duplicate fingerprint "
                                        + fp + " (assigned to multiple signerIds)");
                    }
                }
            }
        }

        boolean isConfigured() {
            return !signerIdToFingerprints.isEmpty();
        }

        private static Map<String, List<String>> parseAllowlist(String rawCsv) {
            if (rawCsv == null || rawCsv.isBlank()) {
                return Map.of();
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            // Format: "signer-id=fp1,fp2 ; signer-id2=fp3"
            for (String entry : rawCsv.split(";")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0 || eq >= trimmed.length() - 1) {
                    throw new IllegalArgumentException(
                            "quorum.psbt.signer-tls-fingerprints: invalid entry '" + trimmed
                                    + "'. Expected format: signerId=fp1,fp2");
                }
                String signerId = trimmed.substring(0, eq).trim();
                String fpsRaw = trimmed.substring(eq + 1).trim();
                if (signerId.isEmpty() || fpsRaw.isEmpty()) {
                    continue;
                }
                List<String> fps = java.util.Arrays.stream(fpsRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(SignerFingerprintAllowlist::normalizeFingerprint)
                        .toList();
                if (result.containsKey(signerId)) {
                    throw new IllegalArgumentException(
                            "quorum.psbt.signer-tls-fingerprints: duplicate signerId '"
                                    + signerId + "'");
                }
                result.put(signerId, fps);
            }
            return result;
        }

        private static String normalizeFingerprint(String fingerprint) {
            String hex = fingerprint.replaceAll("[:\\s]", "").toLowerCase(Locale.ROOT);
            if (!hex.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "quorum.psbt.signer-tls-fingerprints: invalid fingerprint format: "
                                + fingerprint);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 2) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(hex, i, i + 2);
            }
            return sb.toString();
        }

        static String fingerprintOf(X509Certificate cert) {
            try {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                byte[] digest = sha256.digest(cert.getEncoded());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < digest.length; i++) {
                    if (i > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format("%02X", digest[i]));
                }
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException | CertificateEncodingException e) {
                throw new IllegalStateException("Unable to compute certificate fingerprint.", e);
            }
        }
    }

    private String signerId(int index) {
        return index < signerIds.size() ? signerIds.get(index) : "signer-" + (index + 1);
    }

    private String signerLabel(String seatId, String cryptographicKey) {
        String keyHash = sha256(cryptographicKey);
        return firstNonBlank(seatId, "signer") + "#" + keyHash.substring(0, 16);
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
            String status,
            String intentId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", meshOnly ? "BITCOIN_CORE_VAULT_MESH" : "BITCOIN_CORE_QUORUM");
        metadata.put("status", status);
        metadata.put("intentId", intentId);
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

    private String metadataJson(
            String fundedPsbtHash,
            String combinedPsbtHash,
            String rawTxHash,
            List<String> acceptedSigners,
            long feeSats,
            String txid,
            String status) {
        return metadataJson(fundedPsbtHash, combinedPsbtHash, rawTxHash, acceptedSigners,
                feeSats, txid, status, null);
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
