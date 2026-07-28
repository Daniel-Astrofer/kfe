package com.kerosene.kfe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "kfe.pricing")
public class KfePricingPolicy {

    private int version = 1;
    private Map<String, RailPricing> rails = new HashMap<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, RailPricing> getRails() {
        return rails;
    }

    public void setRails(Map<String, RailPricing> rails) {
        this.rails = rails != null ? rails : new HashMap<>();
    }

    public RailPricing forRailDirection(String railKey) {
        RailPricing pricing = rails.get(railKey);
        if (pricing == null || !pricing.isEnabled()) {
            return null;
        }
        return pricing;
    }

    public static class RailPricing {

        private int basisPoints;
        private Integer minSats;
        private Integer maxSats;
        private boolean enabled;

        public int getBasisPoints() {
            return basisPoints;
        }

        public void setBasisPoints(int basisPoints) {
            this.basisPoints = basisPoints;
        }

        public Integer getMinSats() {
            return minSats;
        }

        public void setMinSats(Integer minSats) {
            this.minSats = minSats;
        }

        public Integer getMaxSats() {
            return maxSats;
        }

        public void setMaxSats(Integer maxSats) {
            this.maxSats = maxSats;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
