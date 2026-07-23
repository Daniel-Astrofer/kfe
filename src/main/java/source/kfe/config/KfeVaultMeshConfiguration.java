package source.kfe.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

import java.time.Instant;

/**
 * Fallback when {@code kfe.vaultmesh.enabled} is false (no HTTP client bean).
 */
@Configuration
public class KfeVaultMeshConfiguration {

    @Bean
    @ConditionalOnMissingBean(VaultMeshSettlementPort.class)
    public VaultMeshSettlementPort kfeVaultMeshSettlementPort() {
        return intent -> new VaultMeshReceipt(
                intent == null ? null : intent.intentId(),
                VaultMeshReceipt.Status.REJECTED,
                "MESH_DISABLED",
                null,
                Instant.now().toEpochMilli());
    }
}
