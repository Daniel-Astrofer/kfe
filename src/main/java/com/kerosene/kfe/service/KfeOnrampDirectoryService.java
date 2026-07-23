package com.kerosene.kfe.service;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Directory of third-party onramp/offramp URLs for beta.
 * Kerosene does not process fiat; these are outbound links only.
 *
 * Configure any of:
 *   kfe.onramp.url.buy
 *   kfe.onramp.url.sell
 *   kfe.onramp.url.help
 *   kfe.onramp.url.moonpay
 *   kfe.onramp.url.transak
 *   ...
 * Env form: KFE_ONRAMP_URL_MOONPAY=https://...
 */
@Service
public class KfeOnrampDirectoryService {

    private static final String PREFIX = "kfe.onramp.url.";

    private final Map<String, String> urls;

    public KfeOnrampDirectoryService(Environment environment) {
        this.urls = Map.copyOf(loadUrls(environment));
    }

    public Map<String, String> urls() {
        return urls;
    }

    private static Map<String, String> loadUrls(Environment environment) {
        Map<String, String> found = new LinkedHashMap<>();

        // Always try the canonical beta keys first so order is stable.
        putIfConfigured(environment, found, "buy");
        putIfConfigured(environment, found, "sell");
        putIfConfigured(environment, found, "help");
        putIfConfigured(environment, found, "moonpay");
        putIfConfigured(environment, found, "transak");
        putIfConfigured(environment, found, "ramp");
        putIfConfigured(environment, found, "onramper");
        putIfConfigured(environment, found, "stripe");
        putIfConfigured(environment, found, "coinbase");
        putIfConfigured(environment, found, "banxa");
        putIfConfigured(environment, found, "mercuryo");
        putIfConfigured(environment, found, "wert");
        putIfConfigured(environment, found, "gatefi");

        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> propertySource : configurable.getPropertySources()) {
                if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String propertyName : enumerable.getPropertyNames()) {
                    if (propertyName == null || !propertyName.startsWith(PREFIX)) {
                        continue;
                    }
                    String key = propertyName.substring(PREFIX.length()).trim().toLowerCase(Locale.ROOT);
                    if (key.isEmpty() || found.containsKey(key)) {
                        continue;
                    }
                    putIfConfigured(environment, found, key);
                }
            }
        }

        return found;
    }

    private static void putIfConfigured(Environment environment, Map<String, String> urls, String key) {
        String value = environment.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            return;
        }
        String cleaned = value.trim();
        if (!cleaned.startsWith("https://") && !cleaned.startsWith("http://")) {
            return;
        }
        urls.put(key, cleaned);
    }
}
