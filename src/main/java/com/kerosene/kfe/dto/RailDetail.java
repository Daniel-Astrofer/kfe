package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfeRail;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-rail payload for a multi-rail payment request.
 */
public record RailDetail(
        @JsonProperty("rail") KfeRail rail,
        /** On-chain address (tb1…) or ln:hash for Lightning. */
        @JsonProperty("address") String address,
        /** BOLT11 invoice, only when rail == LIGHTNING. */
        @JsonProperty("paymentRequest") String paymentRequest,
        /** Payment hash, only when rail == LIGHTNING. */
        @JsonProperty("paymentHash") String paymentHash) {
}
