package com.kerosene.kfe.maintenance;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface KfeMaintenanceStore {
    record Control(KfeMaintenanceGuard.Mode mode, String changeId, long revision) { }
    record Admission(UUID id, long revision) { }
    record Observation(Control control, Instant observedAt, Map<String, Long> blockers) { }

    Control transition(KfeMaintenanceGuard.Action action, KfeMaintenanceGuard.Command command, long operatorId);
    Admission admit(String operation);
    void resolve(UUID admissionId, boolean certainCompletion);
    Admission captureContinuation(UUID parentId, String operation, boolean waitingForCommit);
    void releaseContinuation(UUID admissionId, boolean committed);
    Admission claimContinuation(UUID admissionId);
    Observation observe();
}
