package source.kfe.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import source.common.infra.logging.LogSanitizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KfeQuorumPsbtSigningService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KfeQuorumPsbtSigningService.class);

    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final int requiredSignatures;
    private final int fundingConfirmationTarget;
    private final List<String> signerUrls;
    private final List<String> signerApiKeys;
    private final List<String> signerIds;
    private final boolean requireSignerIdentity;

    public KfeQuorumPsbtSigningService(
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Qualifier("custodyRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${quorum.psbt.required-signatures:2}") int requiredSignatures,
            @Value("${quorum.psbt.funding-confirmation-target:6}") int fundingConfirmationTarget,
            @Value("${quorum.psbt.signer-urls:}") String signerUrls,
            @Value("${quorum.psbt.signer-api-keys:}") String signerApiKeys,
            @Value("${quorum.psbt.signer-ids:}") String signerIds,
            @Value("${quorum.psbt.require-signer-identity:true}") boolean requireSignerIdentity) {
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.requiredSignatures = Math.max(1, requiredSignatures);
        this.fundingConfirmationTarget = Math.max(1, fundingConfirmationTarget);
        this.signerUrls = splitCsv(signerUrls);
        this.signerApiKeys = splitCsv(signerApiKeys);
        this.signerIds = splitCsv(signerIds);
        this.requireSignerIdentity = requireSignerIdentity;
    }

    public OnchainFundingPreflight preflight(KfeOnchainPaymentGateway.OnchainPreflightCommand command) {
        BitcoinCoreRpcClient bitcoinCore = requireBitcoinCore();
        requireSignerCapacity();

        BitcoinCoreRpcClient.FundedPsbt fundedPsbt = bitcoinCore.createFundedPsbt(
                command.destinationAddress(),
                command.amountSats(),
                fundingConfirmationTarget);
        validateFundedPsbt(fundedPsbt, command.maxFeeSats());
        return new OnchainFundingPreflight(
                fundedPsbt.feeSats(),
                sha256(fundedPsbt.psbt()),
                signerUrls.size());
    }

    public OnchainExecution execute(KfeOnchainPaymentGateway.OnchainPaymentCommand command) {
        BitcoinCoreRpcClient bitcoinCore = requireBitcoinCore();
        requireSignerCapacity();

        BitcoinCoreRpcClient.FundedPsbt fundedPsbt = bitcoinCore.createFundedPsbt(
                command.destinationAddress(),
                command.amountSats(),
                fundingConfirmationTarget);
        validateFundedPsbt(fundedPsbt, command.maxFeeSats());
        String fundedPsbtHash = sha256(fundedPsbt.psbt());

        log.info(
                "[KFE-PSBT] event=PSBT_CREATED userRef={} destinationRef={} walletNameRef={} amountSats={}",
                LogSanitizer.fingerprint(String.valueOf(command.userId())),
                LogSanitizer.fingerprint(command.destinationAddress()),
                LogSanitizer.fingerprint(command.walletName()),
                command.amountSats());

        List<String> partialPsbts = new ArrayList<>();
        partialPsbts.add(fundedPsbt.psbt());
        List<String> acceptedSigners = new ArrayList<>();

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
                    "Quorum signing failed: " + acceptedSigners.size() + " of " + requiredSignatures + " signers responded.");
        }

        String combinedPsbt = bitcoinCore.combinePsbt(partialPsbts);
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
                "[KFE-PSBT] event=PSBT_BROADCAST userRef={} txidRef={} signedBy={} destinationRef={}",
                LogSanitizer.fingerprint(String.valueOf(command.userId())),
                LogSanitizer.fingerprint(txid),
                acceptedSigners.size(),
                LogSanitizer.fingerprint(command.destinationAddress()));

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
        if (signerUrls.isEmpty()) {
            throw new IllegalStateException("No quorum PSBT signer endpoints are configured.");
        }
        if (signerUrls.size() < requiredSignatures) {
            throw new IllegalStateException(
                    "Quorum signing requires " + requiredSignatures + " signers but only "
                            + signerUrls.size() + " endpoints are configured.");
        }
    }

    private void validateFundedPsbt(BitcoinCoreRpcClient.FundedPsbt fundedPsbt, long maxFeeSats) {
        if (fundedPsbt.psbt() == null || fundedPsbt.psbt().isBlank()) {
            throw new IllegalStateException("Bitcoin Core did not return a PSBT.");
        }
        if (maxFeeSats > 0L && fundedPsbt.feeSats() > maxFeeSats) {
            throw new IllegalStateException("Funded PSBT fee exceeds configured on-chain fee cap.");
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
        metadata.put("provider", "BITCOIN_CORE_QUORUM");
        metadata.put("status", status);
        metadata.put("fundedPsbtHash", fundedPsbtHash);
        metadata.put("combinedPsbtHash", combinedPsbtHash);
        metadata.put("rawTxHash", rawTxHash);
        metadata.put("acceptedSigners", acceptedSigners);
        metadata.put("acceptedSignerCount", acceptedSigners.size());
        metadata.put("requiredSignatures", requiredSignatures);
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
