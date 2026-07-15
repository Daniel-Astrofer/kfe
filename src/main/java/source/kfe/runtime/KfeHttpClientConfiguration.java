package source.kfe.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
public class KfeHttpClientConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    /** scantxoutset over testnet4 UTXO set often exceeds 20s under load. */
    private static final Duration BITCOIND_READ_TIMEOUT = Duration.ofSeconds(120);

    @Bean("custodyRestTemplate")
    public RestTemplate custodyRestTemplate(RestTemplateBuilder builder) {
        return externalRailTemplate(builder);
    }

    @Bean("btcpayRestTemplate")
    public RestTemplate btcpayRestTemplate(RestTemplateBuilder builder) {
        return externalRailTemplate(builder);
    }

    @Bean("bitcoindRestTemplate")
    public RestTemplate bitcoindRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(BITCOIND_READ_TIMEOUT)
                .build();
    }

    @Bean("lndRestTemplate")
    public RestTemplate lndRestTemplate(RestTemplateBuilder builder) {
        return externalRailTemplate(builder);
    }

    private RestTemplate externalRailTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .build();
    }
}
