package source.kfe.service;

import org.springframework.stereotype.Component;
import source.kfe.dto.KfeAddressResponse;
import source.kfe.dto.KfeTransactionResponse;
import source.kfe.dto.KfeWalletResponse;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.UUID;

@Component
public class KfeResponseMapper {

    private final KfeWalletAddressRepository addressRepository;
    private final KfeWalletRepository walletRepository;

    public KfeResponseMapper(
            KfeWalletAddressRepository addressRepository,
            KfeWalletRepository walletRepository) {
        this.addressRepository = addressRepository;
        this.walletRepository = walletRepository;
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
                wallet.getCreatedAt(),
                wallet.getUpdatedAt());
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
                address.getCreatedAt(),
                address.getRetiredAt());
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

        return new KfeTransactionResponse(
                tx.getId(),
                tx.getStatus(),
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
                tx.getConfirmations(),
                tx.getFailureCode(),
                sanitizeFailureMessage(tx.getFailureCode(), tx.getFailureMessage()),
                toUtcInstant(tx.getCreatedAt()),
                toUtcInstant(tx.getUpdatedAt()));
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

        String provider = tx.getProvider() != null ? tx.getProvider().toUpperCase() : "";
        boolean cold = provider.contains("COLD") || provider.contains("WATCH_ONLY");
        if (inbound) {
            return cold ? "Rede Bitcoin (cold)" : "Rede Bitcoin (on-chain)";
        }
        // Outbound: prefer destination address / external reference, shortened.
        String ext = tx.getExternalReference();
        if (hasText(ext)) {
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
     * KFE stores {@code LocalDateTime} in the JVM clock (UTC in containers). Emit Instant with
     * explicit UTC so Flutter never treats wall-clock UTC as local time.
     */
    private static java.time.Instant toUtcInstant(java.time.LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return value.atZone(java.time.ZoneOffset.UTC).toInstant();
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
     * Consumer clients must not receive stack traces or internal paths.
     * Prefer empty when code is unknown; FE maps codes to localized copy.
     */
    static String sanitizeFailureMessage(String failureCode, String failureMessage) {
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
            case "REQUIRES_RECONCILIATION" -> "Needs review.";
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
