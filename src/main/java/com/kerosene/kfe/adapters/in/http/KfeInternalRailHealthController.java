package com.kerosene.kfe.adapters.in.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.financial.operations.FinancialRailHealthPort;
import com.kerosene.kfe.adapters.out.integration.rail.KfeFinancialRailHealthAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Exposes authenticated internal health views for custody and configured financial rail providers. */
@RestController
@RequestMapping("/internal/kfe/rail-health")
public class KfeInternalRailHealthController {

    /** Adapter that queries provider health without exposing provider implementation details to callers. */
    private final KfeFinancialRailHealthAdapter railHealthAdapter;
    /** Shared internal credential; an unset value disables these endpoints with service unavailable. */
    private final String internalSecret;

    /**
     * Creates the internal provider health controller.
     *
     * @param railHealthAdapter provider-health integration adapter
     * @param internalSecret secret expected in the internal request header
     */
    public KfeInternalRailHealthController(
            KfeFinancialRailHealthAdapter railHealthAdapter,
            @Value("${kfe.internal.shared-secret:}") String internalSecret) {
        this.railHealthAdapter = railHealthAdapter;
        this.internalSecret = internalSecret;
    }

    /** Returns custody-provider health after validating the internal shared secret.
     * @param credential optional {@code X-KFE-Internal-Secret} header
     * @return current custody provider health
     */
    @GetMapping("/custody-provider")
    public FinancialRailHealthPort.ProviderHealth custodyProvider(
            @RequestHeader(name = "X-KFE-Internal-Secret", required = false) String credential) {
        verifyCredential(credential);
        return railHealthAdapter.custodyProviderHealth();
    }

    /** Returns health snapshots for active external rail providers after validating the shared secret.
     * @param credential optional {@code X-KFE-Internal-Secret} header
     * @return list of active rail provider health snapshots
     */
    @GetMapping("/external-providers")
    public List<FinancialRailHealthPort.ProviderHealth> activeRailProviders(
            @RequestHeader(name = "X-KFE-Internal-Secret", required = false) String credential) {
        verifyCredential(credential);
        return railHealthAdapter.activeRailProviderHealth();
    }

    /** Rejects access if the internal credential is unconfigured or does not match. */
    private void verifyCredential(String credential) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KFE internal shared secret is not configured");
        }
        if (credential == null || credential.isBlank() || !constantTimeEquals(internalSecret, credential)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid KFE internal credential");
        }
    }

    /** Compares the configured and presented secret with a constant-time byte comparison. */
    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
