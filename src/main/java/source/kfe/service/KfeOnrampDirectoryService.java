package source.kfe.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KfeOnrampDirectoryService {

    private final String buyUrl;
    private final String sellUrl;
    private final String helpUrl;

    public KfeOnrampDirectoryService(
            @Value("${kfe.onramp.url.buy:}") String buyUrl,
            @Value("${kfe.onramp.url.sell:}") String sellUrl,
            @Value("${kfe.onramp.url.help:}") String helpUrl) {
        this.buyUrl = clean(buyUrl);
        this.sellUrl = clean(sellUrl);
        this.helpUrl = clean(helpUrl);
    }

    public Map<String, String> urls() {
        Map<String, String> urls = new LinkedHashMap<>();
        putIfPresent(urls, "buy", buyUrl);
        putIfPresent(urls, "sell", sellUrl);
        putIfPresent(urls, "help", helpUrl);
        return Map.copyOf(urls);
    }

    private void putIfPresent(Map<String, String> urls, String key, String value) {
        if (value != null && !value.isBlank()) {
            urls.put(key, value);
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
