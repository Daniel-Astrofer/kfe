package com.kerosene.kfe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KfeKeroseneNodeDirectoryTest {

    @Test
    void authorizesOnlyHttpsOnionHostsAndIgnoresServicePort() {
        String node = KfeKeroseneNodeDirectory.onionHost(
                "https://" + "a".repeat(56) + ".onion:8800");
        String vault = KfeKeroseneNodeDirectory.onionHost(
                "https://" + "a".repeat(56) + ".onion:7801");
        assertThat(vault).isEqualTo(node);
    }

    @Test
    void rejectsClearnetAndKubernetesServiceDns() {
        assertThatThrownBy(() -> KfeKeroseneNodeDirectory.onionHost("https://vault:7801"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KfeKeroseneNodeDirectory.onionHost("http://example.onion:7801"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KfeKeroseneNodeDirectory.onionHost("https://short.onion:7801"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsConfiguredVaultAbsentFromVerifiedRoster() {
        String authorized = "a".repeat(56) + ".onion";
        String unknown = "b".repeat(56) + ".onion";

        assertThatThrownBy(() -> KfeKeroseneNodeDirectory.requireAuthorized(
                        List.of("https://" + unknown + ":7801"), Set.of(authorized)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent from verified Kerosene Node membership");
    }
}
