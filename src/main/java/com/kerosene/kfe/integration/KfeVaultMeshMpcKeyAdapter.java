package com.kerosene.kfe.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.kerosene.common.financial.FinancialMpcKeyPort;
import com.kerosene.common.vaultmesh.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

import java.util.UUID;

/**
 * Real {@link FinancialMpcKeyPort} backed by the vault mesh.
 *
 * <p>Custody in the vault-mesh model is <b>per-bucket</b>, not per-wallet: all USERS
 * custodial-onchain funds are held by a single shared Taproot group key
 * ({@code tr()} / {@code tb1p}) whose FROST shares live in the vault mesh. There is no
 * per-wallet MPC keygen. Therefore {@code keygenWallet} does not mint a fresh key; it
 * resolves the shared USERS bucket Taproot public key from the mesh
 * ({@code GET /v1/bitcoin/deposit?bucket=USERS}) and returns it as the wallet's
 * {@code mpcPublicKey}.
 *
 * <p>Downstream the stored value is only used as a presence flag
 * ({@code mpcKeyConfigured}); the actual signing path goes through
 * {@link VaultMeshSettlementPort#signPsbt} against the same shared key. Returning the
 * bucket shared key keeps wallet creation consistent with custody reality.
 *
 * <p>Active only when {@code kfe.vaultmesh.enabled=true}; otherwise the dev fallback in
 * {@code KfeFinancialFallbackConfiguration} (which throws / dev-escapes) wins. Fails
 * closed: if the mesh cannot provide the USERS deposit key, wallet creation must not
 * proceed — the caller marks the wallet {@code KEYGEN_FAILED}.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public class KfeVaultMeshMpcKeyAdapter implements FinancialMpcKeyPort {

    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshMpcKeyAdapter.class);

    private final ObjectProvider<VaultMeshSettlementPort> settlementPort;

    public KfeVaultMeshMpcKeyAdapter(ObjectProvider<VaultMeshSettlementPort> settlementPort) {
        this.settlementPort = settlementPort;
    }

    @Override
    public String keygenWallet(UUID walletId, Long userId) {
        VaultMeshSettlementPort port = settlementPort.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException(
                    "Vault mesh settlement port unavailable; cannot provision MPC public key.");
        }
        VaultMeshDepositInfo deposit = port.getUsersDepositAddress();
        if (deposit == null) {
            throw new IllegalStateException(
                    "Vault mesh USERS deposit key unavailable; cannot provision MPC public key.");
        }
        String publicKey = resolvePublicKey(deposit);
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalStateException(
                    "Vault mesh USERS deposit key returned an empty public key.");
        }
        log.info(
                "[KFE MPC] Provisioned bucket-shared Taproot public key for walletId={} userId={}",
                walletId,
                userId);
        return publicKey;
    }

    /**
     * Prefer the raw x-only Taproot internal key, then the tweaked output pubkey, then
     * the on-chain deposit address as a last-resort stable identifier. Any of these is a
     * non-empty stable string tied to the shared USERS bucket custody key.
     */
    private String resolvePublicKey(VaultMeshDepositInfo deposit) {
        if (deposit.xonlyPubkeyHex() != null && !deposit.xonlyPubkeyHex().isBlank()) {
            return deposit.xonlyPubkeyHex().trim();
        }
        if (deposit.outputPubkeyHex() != null && !deposit.outputPubkeyHex().isBlank()) {
            return deposit.outputPubkeyHex().trim();
        }
        if (deposit.address() != null && !deposit.address().isBlank()) {
            return deposit.address().trim();
        }
        return null;
    }
}
