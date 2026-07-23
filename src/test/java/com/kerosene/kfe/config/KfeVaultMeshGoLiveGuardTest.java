package com.kerosene.kfe.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KfeVaultMeshGoLiveGuardTest {

    @Test
    void meshOnlyRequiresVaultMeshEnabled() {
        KfeVaultMeshGoLiveGuard guard = new KfeVaultMeshGoLiveGuard(true, false, false);
        assertThrows(IllegalStateException.class, () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void meshOnlyRequiresMpcSigningOff() {
        KfeVaultMeshGoLiveGuard guard = new KfeVaultMeshGoLiveGuard(true, true, true);
        assertThrows(IllegalStateException.class, () -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void meshOnlyHappyPath() {
        KfeVaultMeshGoLiveGuard guard = new KfeVaultMeshGoLiveGuard(true, true, false);
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }

    @Test
    void dualPathStillAllowedWhenMeshOnlyOff() {
        KfeVaultMeshGoLiveGuard guard = new KfeVaultMeshGoLiveGuard(false, false, true);
        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments()));
    }
}
