package com.kerosene.kfe.maintenance;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explicit unit-test admission; never a production default or a coverage claim. */
public final class MaintenanceTestFixture {
    private MaintenanceTestFixture() {}

    public static KfeMaintenanceGuard active() {
        KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
        org.mockito.Mockito.lenient().when(store.admit(anyString())).thenAnswer(ignored ->
                new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0));
        org.mockito.Mockito.lenient().when(store.observe()).thenAnswer(ignored ->
                new KfeMaintenanceStore.Observation(
                        new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.ACTIVE, null, 0),
                        java.time.Instant.now(), java.util.Map.of()));
        return new KfeMaintenanceService(store);
    }
}
