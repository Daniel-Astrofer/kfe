package com.kerosene.kfe.bootstrap.config.financial;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KfeVaultMeshGoLiveGuardTest {

    private static KfeVaultMeshGoLiveGuard guard(
            boolean meshOnly,
            boolean enabled,
            boolean mpc,
            boolean requireMtls,
            boolean tlsEnabled,
            String apiToken,
            String transport,
            boolean hostnameVerification,
            String network,
            boolean localSigner,
            int members,
            int threshold) {
        return new KfeVaultMeshGoLiveGuard(
                meshOnly, enabled, mpc, requireMtls, tlsEnabled, apiToken,
                "/cert.pem", "/key.pem", "/ca.pem", "", "", transport,
                hostnameVerification, network, localSigner, members, threshold,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static KfeVaultMeshGoLiveGuard validGuard() {
        return guard(true, true, false, true, true, "", "tor", true, "testnet3", false, 3, 2);
    }

    @Test
    void acceptsOnlyCanonicalRuntime() {
        assertDoesNotThrow(() -> validGuard().run(new DefaultApplicationArguments()));
    }

    @Test
    void rejectsAlternativeSigningPaths() {
        assertThrows(IllegalStateException.class, () ->
                guard(false, true, false, true, true, "", "tor", true, "testnet3", false, 3, 2)
                        .run(new DefaultApplicationArguments()));
        assertThrows(IllegalStateException.class, () ->
                guard(true, true, true, true, true, "", "tor", true, "testnet3", false, 3, 2)
                        .run(new DefaultApplicationArguments()));
        assertThrows(IllegalStateException.class, () ->
                guard(true, true, false, true, true, "", "tor", true, "testnet3", true, 3, 2)
                        .run(new DefaultApplicationArguments()));
    }

    @Test
    void rejectsTransportOrIdentityDowngrade() {
        assertThrows(IllegalStateException.class, () ->
                guard(true, true, false, false, true, "", "tor", true, "testnet3", false, 3, 2)
                        .run(new DefaultApplicationArguments()));
        assertThrows(IllegalStateException.class, () ->
                guard(true, true, false, true, true, "token", "tor", true, "testnet3", false, 3, 2)
                        .run(new DefaultApplicationArguments()));
        assertThrows(IllegalStateException.class, () ->
                guard(true, true, false, true, true, "", "direct", true, "testnet3", false, 3, 2)
                        .run(new DefaultApplicationArguments()));
    }

    @Test
    void rejectsWrongNetworkOrConstitution() {
        assertThrows(IllegalStateException.class, () ->
                guard(true, true, false, true, true, "", "tor", true, "mainnet", false, 3, 2)
                        .run(new DefaultApplicationArguments()));
        assertThrows(IllegalStateException.class, () ->
                guard(true, true, false, true, true, "", "tor", true, "testnet3", false, 3, 1)
                        .run(new DefaultApplicationArguments()));
    }
}
