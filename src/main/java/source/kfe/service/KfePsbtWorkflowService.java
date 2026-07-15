package source.kfe.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.dto.KfeColdWalletPsbtRequest;
import source.kfe.dto.KfePsbtWorkflowResponse;
import source.kfe.dto.KfeSignedPsbtRequest;
import source.kfe.model.KfePsbtWorkflowEntity;
import source.kfe.model.KfePsbtWorkflowStatus;
import source.kfe.rail.BitcoinCoreRpcClient;
import source.common.exception.FinancialProviderUnavailableException;
import source.kfe.repository.KfePsbtWorkflowRepository;

import java.time.LocalDateTime;
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

    public KfePsbtWorkflowService(
            KfePsbtWorkflowRepository workflowRepository,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClientProvider,
            ObjectMapper objectMapper,
            KfeHashService hashService,
            KfeAuditLogService auditLogService) {
        this.workflowRepository = workflowRepository;
        this.bitcoinCoreRpcClientProvider = bitcoinCoreRpcClientProvider;
        this.objectMapper = objectMapper;
        this.hashService = hashService;
        this.auditLogService = auditLogService;
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
        BitcoinCoreRpcClient.FinalizedPsbt finalized = bitcoinCore.finalizePsbt(request.signedPsbt().trim());
        workflow.setSignedPsbt(request.signedPsbt().trim());
        workflow.setSignedPsbtHash(hashService.sha256(request.signedPsbt().trim()));
        workflow.setSignedAt(LocalDateTime.now());
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
            workflow.setBroadcastAt(LocalDateTime.now());
            workflow.setStatus(KfePsbtWorkflowStatus.BROADCAST);
            workflow = workflowRepository.save(workflow);
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

    private String safeReason(String reason) {
        String clean = reason != null && !reason.isBlank() ? reason.trim() : "unavailable";
        return clean.length() > 255 ? clean.substring(0, 255) : clean;
    }
}
