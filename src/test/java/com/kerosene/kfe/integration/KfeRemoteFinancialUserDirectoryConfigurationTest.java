package source.kfe.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import source.common.financial.FinancialUserDirectoryPort;
import source.kfe.config.KfeFinancialFallbackConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class KfeRemoteFinancialUserDirectoryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("kfe"))
            .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
            .withUserConfiguration(
                    KfeFinancialFallbackConfiguration.class,
                    KfeRemoteFinancialUserDirectoryClient.class)
            .withPropertyValues("kfe.internal.shared-secret=credential");

    @Test
    void kfeProfileUsesRemoteDirectoryInsteadOfEmptyFallback() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FinancialUserDirectoryPort.class);
            assertThat(context.getBean(FinancialUserDirectoryPort.class))
                    .isInstanceOf(KfeRemoteFinancialUserDirectoryClient.class);
        });
    }
}
