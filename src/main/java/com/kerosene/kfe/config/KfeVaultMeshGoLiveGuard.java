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
 */
@Component
public class KfeVaultMeshGoLiveGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshGoLiveGuard.class);

    private final boolean meshOnly;
    private final boolean vaultMeshEnabled;
    private final boolean mpcSigningEnabled;

    public KfeVaultMeshGoLiveGuard(
            @Value("${kfe.vaultmesh.mesh-only:false}") boolean meshOnly,
            @Value("${kfe.vaultmesh.enabled:false}") boolean vaultMeshEnabled,
            @Value("${kfe.mpc.signing-enabled:true}") boolean mpcSigningEnabled) {
        this.meshOnly = meshOnly;
        this.vaultMeshEnabled = vaultMeshEnabled;
        this.mpcSigningEnabled = mpcSigningEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!meshOnly) {
            return;
        }
        if (!vaultMeshEnabled) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.mesh-only=true requires kfe.vaultmesh.enabled=true (F8 clean cutover)");
        }
        if (mpcSigningEnabled) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.mesh-only=true requires kfe.mpc.signing-enabled=false (no dual-run mpc)");
        }
        log.warn(
                "vault_mesh_go_live_guard active: mesh-only settlement; mpc signing disabled");
    }
}
