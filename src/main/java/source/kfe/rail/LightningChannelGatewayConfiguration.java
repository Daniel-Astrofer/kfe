package source.kfe.rail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensures a {@link LightningChannelGateway} always exists.
 * When LND REST is enabled, {@link LndRestLightningClient} supplies the bean;
 * otherwise a fail-closed disabled gateway is registered.
 */
@Configuration
public class LightningChannelGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(LightningChannelGateway.class)
    public LightningChannelGateway disabledLightningChannelGateway() {
        return new DisabledLightningChannelGateway();
    }
}
