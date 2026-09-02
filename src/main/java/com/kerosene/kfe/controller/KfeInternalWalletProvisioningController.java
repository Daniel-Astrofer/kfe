package com.kerosene.kfe.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.common.financial.FinancialWalletProvisioningRequest;
import com.kerosene.kfe.integration.KfeFinancialWalletProvisioningAdapter;

@RestController
@RequestMapping("/internal/kfe/wallet-provisioning")
public class KfeInternalWalletProvisioningController {

    private final KfeFinancialWalletProvisioningAdapter walletProvisioningAdapter;

    public KfeInternalWalletProvisioningController(KfeFinancialWalletProvisioningAdapter walletProvisioningAdapter) {
        this.walletProvisioningAdapter = walletProvisioningAdapter;
    }

    @PostMapping("/primary")
    public ResponseEntity<ApiResponse<Void>> ensurePrimaryWalletReady(
            @RequestBody FinancialWalletProvisioningRequest request) {
        if (request == null || request.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        walletProvisioningAdapter.ensurePrimaryWalletReady(request.userId(), request.initialAddress());
        return ResponseEntity.ok(ApiResponse.success("Primary KFE wallet is ready.", null));
    }

}
