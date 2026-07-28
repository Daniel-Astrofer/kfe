package com.kerosene.kfe.integration;

import com.kerosene.common.vaultmesh.VaultMeshReceipt;

import java.time.Instant;
import java.util.List;

/**
 * Cryptographically verified vault-mesh receipt with full audit trail.
 *
 * <p>Wraps the contract-level {@link VaultMeshReceipt} with additional verification
 * fields: constitution binding, epoch, threshold, participant identity, transcript
 * integrity, and optional Ed25519 signature.
 */
public record VaultMeshVerifiedReceipt(
        int receiptVersion,
        String intentId,
        String sessionId,
        String proposalHash,
        String unsignedTransactionHash,
        String signedPsbtHash,
        String constitutionHash,
        int dayEpoch,
        int threshold,
        List<String> participantIds,
        String transcriptHash,
        VaultMeshReceipt.Status decision,
        String signatureProof,
        Instant issuedAt,
        Instant expiresAt
) {
    public VaultMeshReceipt toContractReceipt() {
        return new VaultMeshReceipt(
                intentId,
                decision,
                decision == VaultMeshReceipt.Status.ACCEPTED ? null : "VERIFICATION_FAILED",
                signedPsbtHash,
                issuedAt);
    }
}
