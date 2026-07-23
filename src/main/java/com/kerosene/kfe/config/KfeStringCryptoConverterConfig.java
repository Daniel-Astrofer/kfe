package com.kerosene.kfe.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import com.kerosene.common.persistence.StringCryptoConverter;
import com.kerosene.common.security.StringColumnCryptoPort;

/**
 * Wires {@link StringCryptoConverter}'s static crypto port for KFE standalone.
 *
 * <p>JPA often instantiates converters via {@code @Convert(converter=...)} without Spring,
 * so injection must land on the static holder used by the converter.
 */
@Configuration
public class KfeStringCryptoConverterConfig {

    private static final Logger log = LoggerFactory.getLogger(KfeStringCryptoConverterConfig.class);

    private final StringColumnCryptoPort cryptoPort;

    public KfeStringCryptoConverterConfig(StringColumnCryptoPort cryptoPort) {
        this.cryptoPort = cryptoPort;
    }

    @PostConstruct
    void wireConverter() {
        // Converter is not a Spring bean under scanBasePackages=com.kerosene.kfe — set static port.
        StringCryptoConverter holder = new StringCryptoConverter();
        holder.setCryptoPort(cryptoPort);
        log.info("[KfeStringCryptoConverterConfig] StringCryptoConverter crypto port wired.");
    }
}
