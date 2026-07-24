package com.kerosene.kfe.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
public class KfeHttpClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KfeHttpClientConfiguration.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    /** scantxoutset over testnet UTXO set often exceeds 20s under load. */
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

    /**
     * LND REST is always HTTPS. Local/dev clusters often use the self-signed LND cert;
     * when {@code lightning.lnd.tls.insecure=true} (or legacy {@code LIGHTNING_LND_TLS_ENABLED=false}
     * mapped to that property), skip certificate verification so KFE can reach LND.
     */
    @Bean("lndRestTemplate")
    public RestTemplate lndRestTemplate(
            RestTemplateBuilder builder,
            @Value("${lightning.lnd.tls.insecure:false}") boolean tlsInsecure) {
        RestTemplate template = externalRailTemplate(builder);
        if (tlsInsecure) {
            applyInsecureTls(template);
            log.info("[LND REST] lndRestTemplate created with TLS_INSECURE=true — certificate verification disabled");
        } else {
            log.warn("[LND REST] lndRestTemplate created with TLS_INSECURE=false — certificate verification IS enabled");
        }
        return template;
    }

    private RestTemplate externalRailTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .build();
    }

    private static void applyInsecureTls(RestTemplate template) {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            HostnameVerifier allowAll = (String hostname, SSLSession session) -> true;
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(
                        java.net.HttpURLConnection connection, String httpMethod) throws java.io.IOException {
                    if (connection instanceof HttpsURLConnection https) {
                        https.setSSLSocketFactory(sslContext.getSocketFactory());
                        https.setHostnameVerifier(allowAll);
                    }
                    super.prepareConnection(connection, httpMethod);
                }
            };
            factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
            factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
            template.setRequestFactory(factory);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to configure insecure LND REST TLS", ex);
        }
    }
}
