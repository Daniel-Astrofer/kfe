package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfeRail;

/**
 * Per-rail payload for a multi-rail payment request.
 */
public record RailDetail(
        KfeRail rail,
        /** On-chain address (tb1…) or ln:hash for Lightning. */
        String address,
        /** BOLT11 invoice, only when rail == LIGHTNING. */
        String paymentRequest,
        /** Payment hash, only when rail == LIGHTNING. */
        String paymentHash) {
}
