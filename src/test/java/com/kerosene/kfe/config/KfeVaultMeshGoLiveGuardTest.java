package com.kerosene.kfe.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KfeVaultMeshGoLiveGuardTest {

    private static KfeVaultMeshGoLiveGuard meshOnlyGuard(
            boolean meshOnly,
            boolean enabled,
            boolean mpc,
            boolean requireMtls,
            boolean tlsEnabled,
            String apiToken) {
        return new KfeVaultMeshGoLiveGuard(
                meshOnly,
                enabled,
                mpc,
                requireMtls,
                tlsEnabled,
                false,
                false,
                apiToken,
                requireMtls && tlsEnabled ? "/tmp/cert.pem" : "",
                requireMtls && tlsEnabled ? "/tmp/key.pem" : "",
                requireMtls && tlsEnabled ? "/tmp/ca.pem" : "",
                "",
                "",
                "tor",
                true);
    }

    @Test
    void meshOnlyRequiresVaultMeshEnabled() {
        KfeVaultMeshGoLiveGuard guard = meshOnlyGuard(true, false, false, false, false, "");
        assertThrows(IllegalStateException.class, () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void meshOnlyRequiresMpcSigningOff() {
        KfeVaultMeshGoLiveGuard guard = meshOnlyGuard(true, true, true, false, false, "");
        assertThrows(IllegalStateException.class, () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void meshOnlyHappyPathLabTokenOkWhenMtlsNotRequired() {
        KfeVaultMeshGoLiveGuard guard =
                meshOnlyGuard(true, true, false, false, false, "kerosene-vault-lab-only");
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void dualPathStillAllowedWhenMeshOnlyOff() {
        KfeVaultMeshGoLiveGuard guard = meshOnlyGuard(false, false, true, false, false, "");
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void requireMtlsRefusesApiToken() {
        KfeVaultMeshGoLiveGuard guard =
                new KfeVaultMeshGoLiveGuard(
                        true, true, false, true, true, false, false,
                        "lab-token", "/c", "/k", "/ca", "", "", "direct", true);
        assertThrows(IllegalStateException.class, () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void requireMtlsRequiresTlsEnabled() {
        KfeVaultMeshGoLiveGuard guard =
                new KfeVaultMeshGoLiveGuard(
                        true, true, false, true, false, false, false,
                        "", "/c", "/k", "/ca", "", "", "direct", true);
        assertThrows(IllegalStateException.class, () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void requireMtlsRequiresClientMaterials() {
        KfeVaultMeshGoLiveGuard guard =
                new KfeVaultMeshGoLiveGuard(
                        true, true, false, true, true, false, false, "", "", "", "", "", "", "direct", true);
        assertThrows(IllegalStateException.class, () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void requireMtlsHappyPathPem() {
        KfeVaultMeshGoLiveGuard guard =
                new KfeVaultMeshGoLiveGuard(
                        true, true, false, true, true, false, false,
                        "", "/c", "/k", "/ca", "", "", "direct", true);
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void productionModeRejectsDirectTransport() {
        KfeVaultMeshGoLiveGuard guard = new KfeVaultMeshGoLiveGuard(
                false, true, false, true, true, true, false,
                "", "/c", "/k", "/ca", "", "", "direct", true);
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void productionModeAcceptsTorTransport() {
        KfeVaultMeshGoLiveGuard guard = new KfeVaultMeshGoLiveGuard(
                false, true, false, true, true, true, false,
                "", "/c", "/k", "/ca", "", "", "tor", true);
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void productionModeAcceptsOnionTransport() {
        KfeVaultMeshGoLiveGuard guard = new KfeVaultMeshGoLiveGuard(
                false, true, false, true, true, true, false,
                "", "/c", "/k", "/ca", "", "", "onion", true);
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void productionModeRejectsDisabledHostnameVerification() {
        KfeVaultMeshGoLiveGuard guard = new KfeVaultMeshGoLiveGuard(
                false, true, false, true, true, true, false,
                "", "/c", "/k", "/ca", "", "", "tor", false);
        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments()));
    }
}
