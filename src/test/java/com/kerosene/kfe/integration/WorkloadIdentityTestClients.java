package com.kerosene.kfe.integration;

import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import com.kerosene.common.security.workload.WorkloadIdentityConfig;

import java.time.Duration;

final class WorkloadIdentityTestClients {

    private WorkloadIdentityTestClients() {
    }

    static InternalServiceRestTemplateFactory legacy(String secret) {
        return new InternalServiceRestTemplateFactory(
                new WorkloadIdentityConfig(false, "", "", "", 8443, Duration.ofSeconds(1)),
                null,
                secret);
    }
}
