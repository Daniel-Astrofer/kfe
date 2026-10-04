package com.kerosene.kfe.wallet.adapters.in.compatibility;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeColdWalletObservationService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeColdWalletPsbtRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfePsbtWorkflowResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeSignedPsbtRequest;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfePsbtWorkflowEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfePsbtWorkflowStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.common.exception.FinancialProviderUnavailableException;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfePsbtWorkflowRepository;
import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.SignedPsbt;
import com.kerosene.kfe.wallet.domain.service.SignedPsbtBindingPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KfePsbtWorkflowService {

    private static final TypeReference<List<KfeColdWalletPsbtRequest.Input>> INPUT_LIST_TYPE =
            new TypeReference<>() {};

    private final KfePsbtWorkflowRepository workflowRepository;
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClientProvider;
    private final ObjectMapper objectMapper;
    private final KfeHashService hashService;
    private final KfeAuditLogService auditLogService;
    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;

    public KfePsbtWorkflowService(
            KfePsbtWorkflowRepository workflowRepository,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClientProvider,
            ObjectMapper objectMapper,
            KfeHashService hashService,
            KfeAuditLogService auditLogService,
            ObjectProvider<KfeColdWalletObservationService> coldObservationService) {
        this.workflowRepository = workflowRepository;
        this.bitcoinCoreRpcClientProvider = bitcoinCoreRpcClientProvider;
        this.objectMapper = objectMapper;
        this.hashService = hashService;
        this.auditLogService = auditLogService;
        this.coldObservationService = coldObservationService;
    }

    @Transactional
    public KfePsbtWorkflowEntity create(
            Long userId,
            UUID walletId,
            String psbt,
            String psbtHash,
            long feeSats,
            long amountSats,
            String destinationAddress,
            List<KfeColdWalletPsbtRequest.Input> inputs) {
        KfePsbtWorkflowEntity workflow = new KfePsbtWorkflowEntity();
        workflow.setUserId(userId);
        workflow.setWalletId(walletId);
        workflow.setStatus(KfePsbtWorkflowStatus.CREATED);
        workflow.setPsbt(psbt);
        workflow.setPsbtHash(psbtHash);
        workflow.setFeeSats(feeSats);
        workflow.setAmountSats(amountSats);
        workflow.setDestinationAddress(destinationAddress);
        workflow.setInputsJson(writeInputs(inputs));
        workflow = workflowRepository.save(workflow);
        auditLogService.record(
                "KFE_PSBT_WORKFLOW_CREATED",
                null,
                walletId,
                null,
                null,
                Map.of(
                        "workflowId", workflow.getId().toString(),
                        "walletId", walletId.toString(),
                        "psbtHash", psbtHash));
        return workflow;
    }

    @Transactional(readOnly = true)
    public List<KfePsbtWorkflowResponse> list(Long userId, UUID walletId) {
        List<KfePsbtWorkflowEntity> workflows = walletId == null
                ? workflowRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : workflowRepository.findByWalletIdAndUserIdOrderByCreatedAtDesc(walletId, userId);
        return workflows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public KfePsbtWorkflowResponse get(Long userId, UUID workflowId) {
        return workflowRepository.findByIdAndUserId(workflowId, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("KFE PSBT workflow not found."));
    }

    @Transactional
    public KfePsbtWorkflowResponse attachSignedPsbt(Long userId, UUID workflowId, KfeSignedPsbtRequest request) {
        if (request == null || request.signedPsbt() == null || request.signedPsbt().isBlank()) {
            throw new IllegalArgumentException("Signed PSBT is required.");
        }
        KfePsbtWorkflowEntity workflow = workflowRepository.findByIdAndUserId(workflowId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE PSBT workflow not found."));
        if (workflow.getStatus() == KfePsbtWorkflowStatus.BROADCAST) {
            throw new IllegalStateException("Broadcast PSBT workflows cannot be modified.");
        }

        BitcoinCoreRpcClient bitcoinCore = requireBitcoinCore();
        String signed = request.signedPsbt().trim();
        // Bind signed PSBT to the workflow: dest amount + allowed inputs only.
        assertSignedPsbtMatchesWorkflow(bitcoinCore, workflow, signed);

        BitcoinCoreRpcClient.FinalizedPsbt finalized = bitcoinCore.finalizePsbt(signed);
        workflow.setSignedPsbt(signed);
        workflow.setSignedPsbtHash(hashService.sha256(signed));
        workflow.setSignedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        if (finalized.complete() && finalized.hex() != null && !finalized.hex().isBlank()) {
            workflow.setRawTxHex(finalized.hex());
            workflow.setRawTxHash(hashService.sha256(finalized.hex()));
            workflow.setStatus(KfePsbtWorkflowStatus.FINALIZED);
        } else {
            workflow.setStatus(KfePsbtWorkflowStatus.SIGNED);
        }
        workflow = workflowRepository.save(workflow);
        auditLogService.record(
                "KFE_PSBT_WORKFLOW_SIGNED",
                null,
                workflow.getWalletId(),
                null,
                null,
                Map.of(
                        "workflowId", workflow.getId().toString(),
                        "status", workflow.getStatus().name(),
                        "signedPsbtHash", workflow.getSignedPsbtHash()));
        return toResponse(workflow);
    }

    /**
     * Ensures the signed PSBT still pays the workflow destination/amount and only spends
     * workflow-approved inputs (prevents client-side PSBT swap attacks).
     */
    private void assertSignedPsbtMatchesWorkflow(
            BitcoinCoreRpcClient bitcoinCore,
            KfePsbtWorkflowEntity workflow,
            String signedPsbt) {
        JsonNode decoded = bitcoinCore.decodePsbt(signedPsbt);
        if (decoded == null || decoded.isNull() || decoded.isMissingNode()) {
            throw new IllegalArgumentException("Unable to decode signed PSBT.");
        }
        JsonNode tx = decoded.path("tx");
        if (tx.isMissingNode() || tx.isNull()) {
            throw new IllegalArgumentException("Signed PSBT is missing transaction payload.");
        }

        List<Outpoint> signedInputs = new java.util.ArrayList<>();
        JsonNode vin = tx.path("vin");
        if (!vin.isArray() || vin.isEmpty()) {
            throw new IllegalArgumentException("Signed PSBT has no inputs.");
        }
        for (JsonNode input : vin) {
            String txid = text(input, "txid");
            int vout = input.path("vout").asInt(-1);
            if (txid == null || vout < 0) {
                throw new IllegalArgumentException("Signed PSBT input is malformed.");
            }
            signedInputs.add(new Outpoint(txid, vout));
        }

        String expectedDest = workflow.getDestinationAddress() != null
                ? workflow.getDestinationAddress().trim()
                : "";
        long expectedAmount = Math.max(0L, workflow.getAmountSats());
        List<SignedPsbt.Output> outputs = new java.util.ArrayList<>();
        JsonNode vout = tx.path("vout");
        if (!vout.isArray()) {
            throw new IllegalArgumentException("Signed PSBT has no outputs.");
        }
        for (JsonNode output : vout) {
            String address = extractOutputAddress(output);
            long valueSats = btcFieldToSats(output.path("value"));
            outputs.add(new SignedPsbt.Output(address, valueSats));
        }
        List<Outpoint> allowedInputs = readInputs(workflow.getInputsJson()).stream()
                .filter(input -> input != null && input.txid() != null && !input.txid().isBlank())
                .map(input -> new Outpoint(input.txid(), input.vout()))
                .toList();
        try {
            SignedPsbtBindingPolicy.requireMatches(
                    new SignedPsbt(signedInputs, outputs),
                    expectedDest,
                    expectedAmount,
                    allowedInputs);
        } catch (com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation violation) {
            throw new IllegalArgumentException(violation.getMessage(), violation);
        }
    }

    private static String extractOutputAddress(JsonNode output) {
        if (output == null) {
            return null;
        }
        JsonNode spk = output.path("scriptPubKey");
        String address = text(spk, "address");
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
            // Some decoders expose sats as integer.
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

    private static String text(JsonNode node, String field) {
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

    private List<KfeColdWalletPsbtRequest.Input> readInputs(String inputsJson) {
        if (inputsJson == null || inputsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(inputsJson, INPUT_LIST_TYPE);
        } catch (Exception exception) {
            return List.of();
        }
    }

    @Transactional
    public KfePsbtWorkflowResponse broadcast(Long userId, UUID workflowId) {
        KfePsbtWorkflowEntity workflow = workflowRepository.findByIdAndUserId(workflowId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE PSBT workflow not found."));
        if (workflow.getStatus() == KfePsbtWorkflowStatus.BROADCAST) {
            return toResponse(workflow);
        }
        if (workflow.getRawTxHex() == null || workflow.getRawTxHex().isBlank()) {
            throw new IllegalStateException("KFE PSBT workflow must be finalized before broadcast.");
        }

        try {
            String txid = requireBitcoinCore().sendRawTransaction(workflow.getRawTxHex());
            workflow.setBroadcastTxid(txid);
            workflow.setBroadcastAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
            workflow.setStatus(KfePsbtWorkflowStatus.BROADCAST);
            workflow = workflowRepository.save(workflow);
            // History row + confirmation tracking (no custodial reserve for cold).
            KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
            if (coldObs != null && txid != null && !txid.isBlank()) {
                try {
                    coldObs.recordColdPsbtBroadcast(
                            userId,
                            workflow.getWalletId(),
                            workflow.getId(),
                            txid,
                            workflow.getAmountSats(),
                            workflow.getFeeSats(),
                            workflow.getDestinationAddress());
                } catch (RuntimeException observationError) {
                    // Broadcast already succeeded — history can be filled by the observation scheduler.
                }
            }
            auditLogService.record(
                    "KFE_PSBT_WORKFLOW_BROADCAST",
                    null,
                    workflow.getWalletId(),
                    null,
                    null,
                    Map.of(
                            "workflowId", workflow.getId().toString(),
                            "txid", txid != null ? txid : ""));
            return toResponse(workflow);
        } catch (RuntimeException exception) {
            workflow.setStatus(KfePsbtWorkflowStatus.FAILED);
            workflow.setFailureMessage(safeReason(exception.getMessage()));
            workflowRepository.save(workflow);
            throw exception;
        }
    }

    private BitcoinCoreRpcClient requireBitcoinCore() {
        BitcoinCoreRpcClient bitcoinCore = bitcoinCoreRpcClientProvider.getIfAvailable();
        if (bitcoinCore == null) {
            throw new FinancialProviderUnavailableException("Bitcoin Core RPC is unavailable for KFE PSBT workflows.");
        }
        return bitcoinCore;
    }

    private KfePsbtWorkflowResponse toResponse(KfePsbtWorkflowEntity workflow) {
        return new KfePsbtWorkflowResponse(
                workflow.getId(),
                workflow.getUserId(),
                workflow.getWalletId(),
                workflow.getStatus(),
                workflow.getPsbt(),
                workflow.getPsbtHash(),
                workflow.getSignedPsbtHash(),
                workflow.getRawTxHash(),
                workflow.getBroadcastTxid(),
                workflow.getAmountSats(),
                workflow.getFeeSats(),
                workflow.getDestinationAddress(),
                readInputs(workflow.getInputsJson()),
                workflow.getFailureMessage(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt(),
                workflow.getSignedAt(),
                workflow.getBroadcastAt());
    }

    private String writeInputs(List<KfeColdWalletPsbtRequest.Input> inputs) {
        try {
            return objectMapper.writeValueAsString(inputs != null ? inputs : List.of());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize KFE PSBT inputs.", exception);
        }
    }

    private String safeReason(String reason) {
        String clean = reason != null && !reason.isBlank() ? reason.trim() : "unavailable";
        return clean.length() > 255 ? clean.substring(0, 255) : clean;
    }
}
