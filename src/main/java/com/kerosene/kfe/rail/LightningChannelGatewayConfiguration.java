package com.kerosene.kfe.rail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensures a {@link LightningChannelGateway} always exists.
 * When LND REST is enabled, {@link LndRestLightningClient} supplies the bean;
 * otherwise a fail-closed disabled gateway is registered.
 *
 * <p>CHANNELS→LND mesh inject defaults to {@link FailClosedChannelsMeshInjectGateway}
 * when vaultmesh is off. With {@code kfe.vaultmesh.enabled=true},
 * {@link VaultMeshChannelsMeshInjectGateway} soft-reserves CHANNELS capital, binds an
 * LND funding address, then commits after open.
 */
@Configuration
public class LightningChannelGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(LightningChannelGateway.class)
    public LightningChannelGateway disabledLightningChannelGateway() {
        return new DisabledLightningChannelGateway();
    }

    @Bean
    @ConditionalOnMissingBean(ChannelsMeshInjectGateway.class)
    public ChannelsMeshInjectGateway failClosedChannelsMeshInjectGateway() {
        return new FailClosedChannelsMeshInjectGateway();
    }
}
