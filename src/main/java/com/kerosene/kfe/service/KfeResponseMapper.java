package com.kerosene.kfe.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.dto.KfeAddressResponse;
import com.kerosene.kfe.dto.KfeProductStatusMapper;
import com.kerosene.kfe.dto.KfeTransactionResponse;
import com.kerosene.kfe.dto.KfeWalletResponse;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletAddressStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.time.Utc;

import java.util.UUID;

@Component
public class KfeResponseMapper {

    private final KfeWalletAddressRepository addressRepository;
    private final KfeWalletRepository walletRepository;
    private final ObjectProvider<KfeTransactionCancellationService> cancellationService;

    public KfeResponseMapper(
            KfeWalletAddressRepository addressRepository,
            KfeWalletRepository walletRepository,
            ObjectProvider<KfeTransactionCancellationService> cancellationService) {
        this.addressRepository = addressRepository;
        this.walletRepository = walletRepository;
        this.cancellationService = cancellationService;
    }

    public KfeWalletResponse toWalletResponse(KfeWalletEntity wallet) {
        String activeAddress = addressRepository
                .findTopByWalletIdAndStatusOrderByCreatedAtDesc(wallet.getId(), KfeWalletAddressStatus.ACTIVE)
                .map(KfeWalletAddressEntity::getAddress)
                .orElse(null);
        return new KfeWalletResponse(
                wallet.getId(),
                wallet.getKind(),
                wallet.getStatus(),
                wallet.getLabel(),
                wallet.getLabel(),
                walletTypeDescription(wallet.getKind()),
                wallet.getAsset(),
                wallet.isSpendable(),
                hasText(wallet.getXpub()),
                hasText(wallet.getMpcPublicKey()),
                activeAddress,
                Utc.toInstant(wallet.getCreatedAt()),
                Utc.toInstant(wallet.getUpdatedAt()));
    }

    public KfeAddressResponse toAddressResponse(KfeWalletAddressEntity address) {
        return new KfeAddressResponse(
                address.getId(),
                address.getWalletId(),
                address.getAddress(),
                address.getAddressRole(),
                address.getStatus(),
                address.getDerivationPath(),
                address.getDerivationIndex(),
                address.getProviderReference(),
                Utc.toInstant(address.getCreatedAt()),
                Utc.toInstant(address.getRetiredAt()));
    }

    public KfeTransactionResponse toTransactionResponse(KfeTransactionEntity tx) {
        return toTransactionResponse(tx, tx.getUserId());
    }

    public KfeTransactionResponse toTransactionResponse(KfeTransactionEntity tx, Long requestingUserId) {
        UUID perspectiveId = perspectiveWalletId(tx, requestingUserId);
        String sourceLabel = walletLabel(tx.getSourceWalletId());
        String destLabel = walletLabel(tx.getDestinationWalletId());
        String perspectiveLabel = walletLabel(perspectiveId);
        if (!hasText(perspectiveLabel)) {
            perspectiveLabel = hasText(sourceLabel) ? sourceLabel : destLabel;
        }
        String counterparty = counterpartyLabel(tx, requestingUserId, sourceLabel, destLabel);
        KfeTransactionCancellationService.CancellationHints cancelHints = cancelHints(tx, requestingUserId);

        return new KfeTransactionResponse(
                tx.getId(),
                tx.getStatus(),
                KfeTransactionStatus.displayStatusOf(tx.getStatus()),
                KfeProductStatusMapper.toProductStatus(tx.getStatus()),
                tx.getRail(),
                tx.getDirection(),
                perspectiveId,
                tx.getSourceWalletId(),
                tx.getDestinationWalletId(),
                emptyToNull(perspectiveLabel),
                emptyToNull(sourceLabel),
                emptyToNull(destLabel),
                emptyToNull(counterparty),
                tx.getGrossAmountSats(),
                tx.getReceiverAmountSats(),
                tx.getNetworkFeeSats(),
                tx.getKeroseneFeeSats(),
                tx.getTotalDebitSats(),
                tx.getDisplayBtcUsd(),
                tx.getDisplayBtcEur(),
                tx.getDisplayBtcBrl(),
                tx.getDisplayAmountUsd(),
                tx.getDisplayAmountEur(),
                tx.getDisplayAmountBrl(),
                // Consumer app never needs quorum internals.
                null,
                0,
                normalizeProvider(tx.getProvider()),
                tx.getProviderReference(),
                tx.getExternalReference(),
                tx.getMemo(),
                tx.getBlockchainTxid(),
                tx.getPaymentHash(),
                displayConfirmations(tx),
                tx.getFailureCode(),
                sanitizeFailureMessage(tx.getFailureCode(), tx.getFailureMessage()),
                toUtcInstant(tx.getCreatedAt()),
                toUtcInstant(tx.getUpdatedAt()),
                cancelHints.cancellable(),
                cancelHints.cancelTarget(),
                cancelHints.paymentRequestId(),
                cancelHints.paymentRequestPublicId(),
                cancelHints.paymentRequestStatus(),
                tx.getBusinessStatus(),
                tx.getNetworkStatus(),
                tx.getAccountingStatus());
    }

    private KfeTransactionCancellationService.CancellationHints cancelHints(
            KfeTransactionEntity tx, Long requestingUserId) {
        KfeTransactionCancellationService service = cancellationService.getIfAvailable();
        if (service == null) {
            return KfeTransactionCancellationService.CancellationHints.none();
        }
        try {
            return service.hintsFor(tx, requestingUserId);
        } catch (RuntimeException ignored) {
            return KfeTransactionCancellationService.CancellationHints.none();
        }
    }

    /**
     * Canonical home/extrato display payload. Every statement writer should use this so
     * frozen rows stay complete (rail, amounts, labels, refs) — incomplete maps were the
     * root cause of sparse Lightning history on the client.
     */
    public java.util.Map<String, Object> buildDisplayPayload(KfeTransactionEntity tx, Long requestingUserId) {
        Long uid = requestingUserId != null ? requestingUserId : tx.getUserId();
        UUID perspectiveId = perspectiveWalletId(tx, uid);
        String sourceLabel = walletLabel(tx.getSourceWalletId());
        String destLabel = walletLabel(tx.getDestinationWalletId());
        String perspectiveLabel = walletLabel(perspectiveId);
        if (!hasText(perspectiveLabel)) {
            perspectiveLabel = hasText(sourceLabel) ? sourceLabel : destLabel;
        }
        String counterparty = counterpartyLabel(tx, uid, sourceLabel, destLabel);

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("transactionId", tx.getId() != null ? tx.getId().toString() : null);
        // Same as transactionId — client list key for in-place merge (never recreate on update).
        payload.put("id", tx.getId() != null ? tx.getId().toString() : null);
        payload.put("status", tx.getStatus() != null ? tx.getStatus().name() : null);
        payload.put("displayStatus", KfeTransactionStatus.displayStatusOf(tx.getStatus()));
        payload.put("productStatus", KfeProductStatusMapper.toProductStatus(tx.getStatus()));
        payload.put("rail", tx.getRail() != null ? tx.getRail().name() : null);
        payload.put("direction", tx.getDirection() != null ? tx.getDirection().name() : null);
        payload.put("walletId", perspectiveId != null ? perspectiveId.toString() : null);
        payload.put("sourceWalletId", uuidString(tx.getSourceWalletId()));
        payload.put("destinationWalletId", uuidString(tx.getDestinationWalletId()));
        payload.put("walletLabel", emptyToNull(perspectiveLabel));
        payload.put("sourceWalletLabel", emptyToNull(sourceLabel));
        payload.put("destinationWalletLabel", emptyToNull(destLabel));
        payload.put("counterpartyLabel", emptyToNull(counterparty));
        payload.put("grossAmountSats", tx.getGrossAmountSats());
        payload.put("receiverAmountSats", tx.getReceiverAmountSats());
        payload.put("networkFeeSats", tx.getNetworkFeeSats());
        payload.put("keroseneFeeSats", tx.getKeroseneFeeSats());
        payload.put("pricingPolicyVersion", tx.getPricingPolicyVersion());
        payload.put("totalDebitSats", tx.getTotalDebitSats());
        payload.put("displayBtcUsd", tx.getDisplayBtcUsd());
        payload.put("displayBtcEur", tx.getDisplayBtcEur());
        payload.put("displayBtcBrl", tx.getDisplayBtcBrl());
        payload.put("displayAmountUsd", tx.getDisplayAmountUsd());
        payload.put("displayAmountEur", tx.getDisplayAmountEur());
        payload.put("displayAmountBrl", tx.getDisplayAmountBrl());
        payload.put("provider", normalizeProvider(tx.getProvider()));
        payload.put("externalReference", tx.getExternalReference());
        payload.put("memo", tx.getMemo());
        payload.put("blockchainTxid", tx.getBlockchainTxid());
        payload.put("paymentHash", tx.getPaymentHash());
        payload.put("confirmations", displayConfirmations(tx));
        payload.put("failureCode", tx.getFailureCode());
        payload.put("businessStatus", tx.getBusinessStatus());
        payload.put("networkStatus", tx.getNetworkStatus());
        payload.put("accountingStatus", tx.getAccountingStatus());
        payload.put("createdAt", toUtcInstant(tx.getCreatedAt()));
        payload.put("updatedAt", toUtcInstant(tx.getUpdatedAt()));
        return payload;
    }

    private static int displayConfirmations(KfeTransactionEntity tx) {
        if (tx.getRail() == KfeRail.LIGHTNING || tx.getRail() == KfeRail.INTERNAL) {
            return 0;
        }
        return tx.getConfirmations();
    }

    private static String uuidString(UUID id) {
        return id != null ? id.toString() : null;
    }

    private String walletLabel(UUID walletId) {
        if (walletId == null) {
            return null;
        }
        return walletRepository.findById(walletId)
                .map(KfeWalletEntity::getLabel)
                .filter(this::hasText)
                .orElse(null);
    }

    private String counterpartyLabel(
            KfeTransactionEntity tx,
            Long requestingUserId,
            String sourceLabel,
            String destLabel) {
        boolean internal = tx.getRail() == KfeRail.INTERNAL
                || tx.getDirection() == KfeDirection.INTERNAL;
        boolean inbound = isInboundForRequester(tx, requestingUserId);

        if (internal) {
            // Peer wallet from requester's perspective.
            if (inbound) {
                return hasText(sourceLabel) ? sourceLabel : "Kerosene";
            }
            return hasText(destLabel) ? destLabel : "Kerosene";
        }

        if (tx.getRail() == KfeRail.LIGHTNING) {
            if (inbound) {
                return "Lightning Network";
            }
            String ph = tx.getPaymentHash();
            if (hasText(ph) && ph.length() >= 12) {
                return "LN " + shorten(ph.trim(), 8, 6);
            }
            String inv = tx.getExternalReference();
            if (hasText(inv) && inv.toLowerCase().startsWith("ln")) {
                return "Fatura Lightning";
            }
            return "Lightning Network";
        }
        String provider = tx.getProvider() != null ? tx.getProvider().toUpperCase() : "";
        boolean cold = provider.contains("COLD") || provider.contains("WATCH_ONLY");
        if (inbound) {
            return cold ? "Rede Bitcoin (cold)" : "Rede Bitcoin (on-chain)";
        }
        // Outbound: prefer destination address / external reference, shortened.
        String ext = tx.getExternalReference();
        if (hasText(ext)) {
            // Never dump full bolt11 into counterparty for non-LN rails.
            if (ext.toLowerCase().startsWith("ln")) {
                return "Fatura Lightning";
            }
            return shorten(ext.trim(), 10, 8);
        }
        if (hasText(destLabel)) {
            return destLabel;
        }
        String txid = tx.getBlockchainTxid();
        if (hasText(txid) && txid.length() >= 16) {
            return shorten(txid.trim(), 10, 8);
        }
        return cold ? "Endereço cold externo" : "Endereço externo";
    }

    private boolean isInboundForRequester(KfeTransactionEntity tx, Long requestingUserId) {
        if (tx.getDirection() == KfeDirection.INBOUND) {
            return true;
        }
        if (tx.getDirection() == KfeDirection.OUTBOUND) {
            return false;
        }
        // Internal: credit when destination is requester's wallet.
        if (requestingUserId != null
                && tx.getDestinationWalletId() != null
                && walletRepository.findByIdAndUserId(tx.getDestinationWalletId(), requestingUserId).isPresent()) {
            return true;
        }
        return false;
    }

    /**
     * Stable provider taxonomy for clients (COLD_OBSERVE, COLD_SPEND, …).
     */
    static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        String p = provider.trim().toUpperCase();
        if (p.contains("COLD_OBSERVER") || p.contains("COLD_OBSERVE") || p.contains("WATCH_ONLY")) {
            return "COLD_OBSERVE";
        }
        if (p.contains("COLD_EXTERNAL") || p.contains("COLD_SPEND") || p.contains("COLD_PSBT")) {
            return "COLD_SPEND";
        }
        if (p.contains("PAYMENT_LINK") || p.contains("PAYLINK")) {
            return "PAYMENT_LINK";
        }
        if (p.contains("LIGHTNING") || p.contains("LND") || p.contains("BOLT")) {
            return "LIGHTNING";
        }
        if (p.contains("BITCOIN") || p.contains("ONCHAIN") || p.contains("CUSTODIAL")) {
            return "CUSTODIAL_ONCHAIN";
        }
        if (p.contains("INTERNAL") || p.contains("LEDGER")) {
            return "INTERNAL_LEDGER";
        }
        return p;
    }

    private static String shorten(String value, int head, int tail) {
        if (value.length() <= head + tail + 1) {
            return value;
        }
        return value.substring(0, head) + "…" + value.substring(value.length() - tail);
    }

    private String emptyToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    /**
     * KFE stores UTC wall-clock in {@code LocalDateTime} columns. Emit Instant with explicit
     * {@code Z} so Flutter converts to the device timezone (e.g. America/Sao_Paulo).
     */
    private static java.time.Instant toUtcInstant(java.time.LocalDateTime value) {
        return Utc.toInstant(value);
    }

    private UUID perspectiveWalletId(KfeTransactionEntity tx, Long requestingUserId) {
        if (requestingUserId == null) {
            return null;
        }
        if (requestingUserId.equals(tx.getUserId())) {
            return tx.getSourceWalletId() != null ? tx.getSourceWalletId() : tx.getDestinationWalletId();
        }
        return tx.getDestinationWalletId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Consumer clients must not receive stack traces, LND JSON, or internal paths.
     * Map known codes + safe substrings of the raw provider message to short copy;
     * FE can still localize from {@code failureCode}.
     */
    static String sanitizeFailureMessage(String failureCode, String failureMessage) {
        String raw = failureMessage != null ? failureMessage.toLowerCase(java.util.Locale.ROOT) : "";
        // Prefer provider-hinted reasons even when code is generic (PROVIDER_FINAL_FAILURE).
        if (raw.contains("invoice expired")) {
            return "Lightning invoice expired.";
        }
        if (raw.contains("amount must not be specified")) {
            return "Invalid Lightning invoice amount.";
        }
        if (raw.contains("self-payment") || raw.contains("self payment")) {
            return "Self-pay is not allowed.";
        }
        if (raw.contains("no route") || raw.contains("unable to find a path") || raw.contains("destination unknown")) {
            return "No Lightning route to destination.";
        }
        if (raw.contains("lnurl") && (raw.contains("failed") || raw.contains("invalid"))) {
            return "Could not resolve LNURL / Lightning Address.";
        }
        if (raw.contains("keysend")) {
            return "Lightning keysend payment failed.";
        }

        if (failureCode == null || failureCode.isBlank()) {
            return null;
        }
        String code = failureCode.trim().toUpperCase();
        return switch (code) {
            case "INSUFFICIENT_FUNDS", "LEDGER_001", "ERR_INSUFFICIENT_BALANCE" ->
                    "Insufficient balance.";
            case "LEDGER_009", "SELF_PAY" -> "Self-pay is not allowed.";
            case "EXPIRED", "LINK_EXPIRED" -> "Payment request expired.";
            case "CANCELLED", "CANCELED" -> "Cancelled.";
            case "NETWORK", "BROADCAST_FAILED", "RPC_ERROR" -> "Network broadcast failed.";
            case "REQUIRES_RECONCILIATION", "PROVIDER_RESULT_UNKNOWN" -> "Needs review.";
            case "PROVIDER_FINAL_FAILURE", "PROVIDER_RETRY_EXHAUSTED" ->
                    "Lightning payment failed.";
            case "PROVIDER_RETRYABLE_FAILURE" -> "Lightning payment temporarily failed.";
            default -> null;
        };
    }

    public String walletTypeDescription(KfeWalletKind kind) {
        if (kind == null) {
            return "Conta Assegurada";
        }
        return switch (kind) {
            case INTERNAL -> "Carteira assegurada pela Kerosene: saldo interno lastreado na carteira de quorum.";
            case CUSTODIAL_ONCHAIN -> "Custodial on-chain: saldo spendable no ledger interno (autoriza saques) e saldo observado na blockchain para reconciliação.";
            case WATCH_ONLY -> "Cold wallet: saldo somente da blockchain (xpub/descriptor). Sem saldo interno spendable; o servidor nunca autoriza gastos.";
            case SYSTEM_FUNDS -> "Fundos Globais";
            case SYSTEM_PROFIT -> "Lucro Kerosene";
        };
    }
}
