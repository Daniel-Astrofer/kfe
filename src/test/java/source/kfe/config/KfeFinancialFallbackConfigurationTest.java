package source.kfe.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import source.common.financial.FinancialMpcKeyPort;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KfeFinancialFallbackConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KfeFinancialFallbackConfiguration.class);

    @Test
    void standaloneMpcKeygenRejectsByDefault() {
        contextRunner.run(context -> {
            FinancialMpcKeyPort mpcKeyPort = context.getBean(FinancialMpcKeyPort.class);
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> mpcKeyPort.keygenWallet(UUID.randomUUID(), 7L));

            assertEquals("KFE standalone MPC key provisioning is unavailable.", exception.getMessage());
        });
    }

    @Test
    void standaloneMpcKeygenCanReturnLocalDevelopmentKeyWhenExplicitlyEnabled() {
        UUID walletId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        contextRunner
                .withPropertyValues(
                        "REGION=LOCAL",
                        "kfe.standalone.mpc.dev-keygen-enabled=true")
                .run(context -> {
                    FinancialMpcKeyPort mpcKeyPort = context.getBean(FinancialMpcKeyPort.class);

                    assertEquals(
                            "kfe-local-dev-mpc-public-key:11111111-2222-3333-4444-555555555555:7",
                            mpcKeyPort.keygenWallet(walletId, 7L));
                });
    }

    @Test
    void standaloneMpcKeygenStillRejectsDevFlagOutsideLocalRegion() {
        contextRunner
                .withPropertyValues(
                        "REGION=PROD",
                        "kfe.standalone.mpc.dev-keygen-enabled=true")
                .run(context -> {
                    FinancialMpcKeyPort mpcKeyPort = context.getBean(FinancialMpcKeyPort.class);
                    IllegalStateException exception = assertThrows(
                            IllegalStateException.class,
                            () -> mpcKeyPort.keygenWallet(UUID.randomUUID(), 7L));

                    assertEquals("KFE standalone MPC key provisioning is unavailable.", exception.getMessage());
                });
    }
}
