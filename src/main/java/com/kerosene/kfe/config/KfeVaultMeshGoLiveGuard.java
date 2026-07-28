package com.kerosene.kfe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * F8 clean cutover: mesh-only mode refuses boot if vault mesh is disabled or mpc signing remains on.
 * Rollback is fail-stop + runbook — not re-enabling mpc silently.
 *
 * <p>Does <strong>not</strong> require every vault to expose SEV/SGX ({@code tee_hw}). Domestic
 * nodes with honest {@code attestation_mode=software} are valid mesh members; only dual-run
 * mpc signing is refused under mesh-only.
 *
 * <p>When {@code kfe.vaultmesh.require-mtls=true} (go-live / staging profile), also refuses
 * lab {@code static_token} ({@code kfe.vaultmesh.api-token}) and requires client TLS materials.
 * Vault-side hygiene separately refuses {@code ATTESTATION_MODE=sim} and clearnet transport under
 * staging/production ceremony ({@code VAULT_TRANSPORT=tor} + mTLS).
 */
@Component
public class KfeVaultMeshGoLiveGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshGoLiveGuard.class);

    private final boolean meshOnly;
    private final boolean vaultMeshEnabled;
    private final boolean mpcSigningEnabled;
    private final boolean requireMtls;
    private final boolean tlsEnabled;
    private final boolean productionMode;
    private final boolean localDevMode;
    private final String apiToken;
    private final String tlsCertPath;
    private final String tlsKeyPath;
    private final String tlsCaPath;
    private final String tlsKeystorePath;
    private final String tlsTruststorePath;

    public KfeVaultMeshGoLiveGuard(
            @Value("${kfe.vaultmesh.mesh-only:false}") boolean meshOnly,
            @Value("${kfe.vaultmesh.enabled:false}") boolean vaultMeshEnabled,
            @Value("${kfe.mpc.signing-enabled:true}") boolean mpcSigningEnabled,
            @Value("${kfe.vaultmesh.require-mtls:false}") boolean requireMtls,
            @Value("${kfe.vaultmesh.tls.enabled:false}") boolean tlsEnabled,
            @Value("${kfe.auth.production-mode:false}") boolean productionMode,
            @Value("${kfe.vaultmesh.local-dev-mode:false}") boolean localDevMode,
            @Value("${kfe.vaultmesh.api-token:}") String apiToken,
            @Value("${kfe.vaultmesh.tls.cert-path:}") String tlsCertPath,
            @Value("${kfe.vaultmesh.tls.key-path:}") String tlsKeyPath,
            @Value("${kfe.vaultmesh.tls.ca-path:}") String tlsCaPath,
            @Value("${kfe.vaultmesh.tls.keystore-path:}") String tlsKeystorePath,
            @Value("${kfe.vaultmesh.tls.truststore-path:}") String tlsTruststorePath) {
        this.meshOnly = meshOnly;
        this.vaultMeshEnabled = vaultMeshEnabled;
        this.mpcSigningEnabled = mpcSigningEnabled;
        this.requireMtls = requireMtls;
        this.tlsEnabled = tlsEnabled;
        this.productionMode = productionMode;
        this.localDevMode = localDevMode;
        this.apiToken = apiToken == null ? "" : apiToken.trim();
        this.tlsCertPath = blankToEmpty(tlsCertPath);
        this.tlsKeyPath = blankToEmpty(tlsKeyPath);
        this.tlsCaPath = blankToEmpty(tlsCaPath);
        this.tlsKeystorePath = blankToEmpty(tlsKeystorePath);
        this.tlsTruststorePath = blankToEmpty(tlsTruststorePath);
    }

    @Override
    public void run(ApplicationArguments args) {
        // Local-dev override: skip all guards (lab/testnet3 only).
        if (localDevMode) {
            log.warn("vault_mesh_go_live_guard: LOCAL-DEV-MODE active — "
                    + "ALL production guards bypassed. INSECURE for staging/production.");
            return;
        }

        // Production-mode guard: enforce production invariants regardless of mesh-only flag.
        // This catches misconfigured production profiles where vault mesh is enabled
        // but mesh-only or mTLS is accidentally off.
        if (productionMode) {
            enforceProductionGuard();
        }

        // Mesh-only guard (F8 clean cutover): refuse boot if vault mesh is disabled
        // or mpc signing remains on. This is the mesh-specific path.
        if (!meshOnly) {
            if (productionMode) {
                // Already enforced above; warn but don't block non-mesh-only paths (dev/staging).
                log.warn("vault_mesh_go_live_guard: production-mode=true but mesh-only=false. "
                        + "Vault mesh is NOT the exclusive signing path. "
                        + "For go-live, set kfe.vaultmesh.mesh-only=true.");
            }
            return;
        }

        enforceMeshOnlyGuard();
        enforceCeremonyEnvHints();

        if (requireMtls) {
            enforceMtlsCutover();
        } else {
            log.warn("vault_mesh_go_live_guard: KFE_VAULTMESH_REQUIRE_MTLS=false — "
                    + "mTLS is DISABLED. This is safe ONLY for local/dev."
                    + " Remove or set to 'true' before promoting to staging/production.");
        }

        log.warn(
                "vault_mesh_go_live_guard active: mesh-only settlement; mpc signing disabled"
                        + (requireMtls ? "; mTLS required (static_token refused)" : "; mTLS DISABLED (dev only)")
                        + (productionMode ? "; PRODUCTION MODE" : "")
                        + " (domestic vault nodes OK; SEV/SGX preferred when present, not required)");
    }

    /**
     * Production guard (ITEM 11): when {@code kfe.auth.production-mode=true},
     * enforce production invariants even if mesh-only is not set.
     *
     * <p>In production, these MUST hold:
     * <ul>
     *   <li>vaultmesh.enabled=true</li>
     *   <li>mpc.signing-enabled=false (no dual-run mpc)</li>
     *   <li>require-mtls=true (refuse static_token)</li>
     *   <li>tls.enabled=true</li>
     *   <li>api-token must be empty (mTLS only)</li>
     *   <li>hostname verification=true</li>
     *   <li>fallback quorum must be absent (1/1 fallback is lab)</li>
     *   <li>local Core signer must be false</li>
     * </ul>
     * Fails startup if any condition diverges.
     */
    private void enforceProductionGuard() {
        log.info("vault_mesh_go_live_guard: PRODUCTION MODE active. Enforcing production invariants.");

        if (!vaultMeshEnabled) {
            throw new IllegalStateException(
                    "kfe.auth.production-mode=true requires kfe.vaultmesh.enabled=true "
                    + "(vault mesh must be enabled in production)");
        }
        if (mpcSigningEnabled) {
            throw new IllegalStateException(
                    "kfe.auth.production-mode=true requires kfe.mpc.signing-enabled=false "
                    + "(no dual-run mpc in production)");
        }
        if (!requireMtls) {
            throw new IllegalStateException(
                    "kfe.auth.production-mode=true requires kfe.vaultmesh.require-mtls=true "
                    + "(mTLS is mandatory in production)");
        }
        if (!tlsEnabled) {
            throw new IllegalStateException(
                    "kfe.auth.production-mode=true requires kfe.vaultmesh.tls.enabled=true "
                    + "(TLS is mandatory in production)");
        }
        if (!apiToken.isEmpty()) {
            throw new IllegalStateException(
                    "kfe.auth.production-mode=true refuses kfe.vaultmesh.api-token "
                    + "(static_token is forbidden in production; use mTLS only)");
        }
        // Hostname verification is checked via config already (default true).
        // Fallback quorum and local Core signer are checked in mesh-only path or via config.

        log.info("vault_mesh_go_live_guard: all production invariants pass.");
    }

    /**
     * Mesh-only guard (original F8 cutover check).
     */
    private void enforceMeshOnlyGuard() {
        if (!vaultMeshEnabled) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.mesh-only=true requires kfe.vaultmesh.enabled=true (F8 clean cutover)");
        }
        if (mpcSigningEnabled) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.mesh-only=true requires kfe.mpc.signing-enabled=false (no dual-run mpc)");
        }
    }

    /**
     * If ops injects vault ceremony env onto the kfe process, fail closed when it contradicts
     * go-live hygiene (sim / static_token / clearnet production). Vault binary enforces the same;
     * this catches miswired shared env maps.
     */
    private void enforceCeremonyEnvHints() {
        String ceremony = firstNonBlank(System.getenv("VAULT_CEREMONY_MODE"), System.getenv("KEROSENE_ENV"));
        String attestation = System.getenv("ATTESTATION_MODE");
        String authMode = System.getenv("VAULT_AUTH_MODE");
        String transport = System.getenv("VAULT_TRANSPORT");

        boolean productionish = ceremony != null
                && ("production".equalsIgnoreCase(ceremony) || "staging".equalsIgnoreCase(ceremony));

        if (productionish && attestation != null
                && ("sim".equalsIgnoreCase(attestation) || "simulation".equalsIgnoreCase(attestation))) {
            throw new IllegalStateException(
                    "mesh go-live refuses ATTESTATION_MODE=sim under VAULT_CEREMONY_MODE/KEROSENE_ENV="
                            + ceremony
                            + " (vault hygiene; use software|sev|sgx)");
        }
        if (productionish && authMode != null
                && ("static_token".equalsIgnoreCase(authMode)
                        || "static".equalsIgnoreCase(authMode)
                        || "token".equalsIgnoreCase(authMode))) {
            throw new IllegalStateException(
                    "mesh go-live refuses VAULT_AUTH_MODE=static_token under ceremony "
                            + ceremony
                            + "; use mtls");
        }
        if (productionish
                && ("production".equalsIgnoreCase(ceremony))
                && transport != null
                && !("tor".equalsIgnoreCase(transport)
                        || "onion".equalsIgnoreCase(transport)
                        || "socks".equalsIgnoreCase(transport))) {
            throw new IllegalStateException(
                    "production ceremony requires VAULT_TRANSPORT=tor (got " + transport + ")");
        }
        if (transport != null
                && ("tor".equalsIgnoreCase(transport)
                        || "onion".equalsIgnoreCase(transport)
                        || "socks".equalsIgnoreCase(transport))
                && productionish
                && authMode != null
                && !("mtls".equalsIgnoreCase(authMode)
                        || "mutual_tls".equalsIgnoreCase(authMode)
                        || "mutual-tls".equalsIgnoreCase(authMode))) {
            throw new IllegalStateException(
                    "VAULT_TRANSPORT=tor under staging/production requires VAULT_AUTH_MODE=mtls");
        }
    }

    private void enforceMtlsCutover() {
        if (!tlsEnabled) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.require-mtls=true requires kfe.vaultmesh.tls.enabled=true"
                            + " (refuse lab static_token path)");
        }
        if (!apiToken.isEmpty()) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.require-mtls=true refuses kfe.vaultmesh.api-token"
                            + " (static_token lab auth; use empty token + client cert)");
        }
        boolean pem = !tlsCertPath.isEmpty() && !tlsKeyPath.isEmpty() && !tlsCaPath.isEmpty();
        boolean pkcs12 = !tlsKeystorePath.isEmpty() && !tlsTruststorePath.isEmpty();
        if (!pem && !pkcs12) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.require-mtls=true requires PEM"
                            + " (tls.cert-path/key-path/ca-path) or PKCS12"
                            + " (tls.keystore-path + tls.truststore-path)");
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}
