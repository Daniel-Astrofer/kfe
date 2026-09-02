package com.kerosene.kfe.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KfeProductionGateConfigTest {

    @Test
    void productionProfileCannotDisableSafetyGate() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertTrue(KfeProductionGateConfig.resolveProductionMode(false, environment));
    }

    @Test
    void explicitFlagCanEnableGateOutsideProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        assertTrue(KfeProductionGateConfig.resolveProductionMode(true, environment));
        assertFalse(KfeProductionGateConfig.resolveProductionMode(false, environment));
    }
}
