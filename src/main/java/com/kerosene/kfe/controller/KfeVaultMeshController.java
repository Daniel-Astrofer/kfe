package com.kerosene.kfe.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.vaultmesh.VaultMeshIntent;
import com.kerosene.common.vaultmesh.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;

/**
 * Lab/internal entry to exercise F4 Intent → vault mesh without changing outbound rails.
 */
@RestController
@RequestMapping("/internal/kfe/vault-mesh")
public class KfeVaultMeshController {

    private final VaultMeshSettlementPort settlementPort;

    public KfeVaultMeshController(VaultMeshSettlementPort settlementPort) {
        this.settlementPort = settlementPort;
    }

    @PostMapping("/intent")
    public ResponseEntity<VaultMeshReceipt> submitIntent(@RequestBody VaultMeshIntent intent) {
        return ResponseEntity.ok(settlementPort.submitIntent(intent));
    }
}
