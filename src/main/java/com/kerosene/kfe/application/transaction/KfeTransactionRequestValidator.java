package com.kerosene.kfe.application.transaction;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.dto.KfeSubmitTransactionRequest;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.service.BitcoinAddressValidator;
import com.kerosene.kfe.service.KfePlatformLightningPolicy;

@Component
public class KfeTransactionRequestValidator {

    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;

    private final BitcoinAddressValidator bitcoinAddressValidator;
    private final KfePlatformLightningPolicy platformLightningPolicy;

    public KfeTransactionRequestValidator(
            BitcoinAddressValidator bitcoinAddressValidator,
            KfePlatformLightningPolicy platformLightningPolicy) {
        this.bitcoinAddressValidator = bitcoinAddressValidator;
        this.platformLightningPolicy = platformLightningPolicy;
    }

    public void validate(KfeSubmitTransactionRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required.");
        }
        if (request.idempotencyKey().length() > 180) {
            throw new IllegalArgumentException("idempotencyKey must have at most 180 characters.");
        }
        if (request.amountSats() <= 0) {
            throw new IllegalArgumentException("amountSats must be positive.");
        }
        if (request.amountSats() > MAX_SATOSHIS) {
            throw new IllegalArgumentException("amountSats exceeds maximum allowed limit (21M BTC).");
        }
        if (request.networkFeeSats() < 0) {
            throw new IllegalArgumentException("networkFeeSats must be non-negative.");
        }
        if (request.networkFeeSats() > MAX_SATOSHIS) {
            throw new IllegalArgumentException("networkFeeSats exceeds maximum allowed limit (21M BTC).");
        }
        if (request.rail() == KfeRail.INTERNAL && request.direction() != KfeDirection.INTERNAL) {
            throw new IllegalArgumentException("INTERNAL rail requires INTERNAL direction.");
        }
        if (request.direction() == KfeDirection.INTERNAL && request.rail() != KfeRail.INTERNAL) {
            throw new IllegalArgumentException("INTERNAL direction requires INTERNAL rail.");
        }
        if (request.rail() != KfeRail.INTERNAL && request.direction() == KfeDirection.OUTBOUND) {
            validateExternalReference(request);
        }
    }

    private void validateExternalReference(KfeSubmitTransactionRequest request) {
        String ref = request.externalReference();
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("externalReference is required for external outbound transactions.");
        }
        if (request.rail() == KfeRail.ONCHAIN) {
            if (!bitcoinAddressValidator.isValidBitcoinAddressForConfiguredNetwork(ref)) {
                throw new IllegalArgumentException("Invalid Bitcoin address format for externalReference.");
            }
            return;
        }
        if (request.rail() == KfeRail.LIGHTNING) {
            // Accept BOLT11, LNURL1…, Lightning Address (user@domain), or keysend node pubkey.
            if (!com.kerosene.kfe.rail.LightningDestinationClassifier.isValidLightningOutboundReference(ref)) {
                throw new IllegalArgumentException(
                        "Invalid Lightning destination for externalReference. "
                                + "Use a BOLT11 invoice (ln…), LNURL1…, Lightning Address (user@domain), "
                                + "or 66-char node pubkey (keysend).");
            }
            // Same-node platform invoices must settle via INTERNAL ledger, not LND.
            platformLightningPolicy.rejectLightningOutboundIfPlatformOwned(ref);
        }
    }
}
