package source.kfe.dto;

import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record KfeTransactionResponse(
        UUID id,
        KfeTransactionStatus status,
        KfeRail rail,
        KfeDirection direction,
        UUID walletId,
        UUID sourceWalletId,
        UUID destinationWalletId,
        /** Human label of the perspective wallet (never a UUID). */
        String walletLabel,
        /** Human label of the source wallet when known. */
        String sourceWalletLabel,
        /** Human label of the destination wallet when known. */
        String destinationWalletLabel,
        /**
         * Safe counterparty label for the requesting user:
         * peer wallet name (internal), external address short form, or network rail name.
         */
        String counterpartyLabel,
        long grossAmountSats,
        long receiverAmountSats,
        long networkFeeSats,
        long keroseneFeeSats,
        long totalDebitSats,
        BigDecimal displayBtcUsd,
        BigDecimal displayBtcEur,
        BigDecimal displayBtcBrl,
        BigDecimal displayAmountUsd,
        BigDecimal displayAmountEur,
        BigDecimal displayAmountBrl,
        String quorumProposalHash,
        int quorumAckCount,
        String provider,
        String providerReference,
        String externalReference,
        String memo,
        String blockchainTxid,
        String paymentHash,
        int confirmations,
        String failureCode,
        String failureMessage,
        // Instant serializes as ISO-8601 with Z so clients convert timezone correctly.
        Instant createdAt,
        Instant updatedAt) {
}
