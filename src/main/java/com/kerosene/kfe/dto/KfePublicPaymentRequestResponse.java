package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfePaymentRequestStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Sanitized public-facing payment request DTO.
 * Exposes only fields safe for unauthenticated consumers.
 * Internal identifiers, payer hints, transaction references, and timestamps are stripped.
 */
public record KfePublicPaymentRequestResponse(
        String publicId,
        String merchantDisplayName,
        BigDecimal amount,
        String currency,
        String publicDescription,
        KfePaymentRequestStatus status,
        Instant expiresAt,
        List<String> payableRails,
        Instant createdAt) {

    public KfePublicPaymentRequestResponse {
        payableRails = payableRails == null || payableRails.isEmpty()
                ? List.of()
                : List.copyOf(payableRails);
    }
}
