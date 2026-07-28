package com.kerosene.kfe.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Lab/internal entry to exercise F4 Intent → vault mesh without changing outbound rails.
 */
@RestController
@RequestMapping("/internal/kfe/vault-mesh")
public class KfeVaultMeshController {

    private final VaultMeshSettlementPort settlementPort;
    private final String internalSecret;

    public KfeVaultMeshController(
            VaultMeshSettlementPort settlementPort,
            @Value("${kfe.internal.shared-secret:}") String internalSecret) {
        this.settlementPort = settlementPort;
        this.internalSecret = internalSecret;
    }

    @PostMapping("/intent")
    public ResponseEntity<VaultMeshReceipt> submitIntent(
            @RequestHeader(name = "X-KFE-Internal-Secret", required = false) String credential,
            @RequestBody VaultMeshIntent intent) {
        verifyCredential(credential);
        return ResponseEntity.ok(settlementPort.submitIntent(intent));
    }

    private void verifyCredential(String credential) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KFE internal shared secret is not configured");
        }
        if (credential == null || credential.isBlank() || !constantTimeEquals(internalSecret, credential)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid KFE internal credential");
        }
    }

    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
