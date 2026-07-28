package com.kerosene.kfe.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KfeBitcoinFinalityPolicy.class)
public class KfeBitcoinFinalityConfiguration {
}
