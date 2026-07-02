package source.kfe.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.kfe.application.financial.FinancialApi;
import source.kfe.dto.KfeAddressResponse;
import source.kfe.dto.KfeColdWalletPsbtRequest;
import source.kfe.dto.KfeColdWalletPsbtResponse;
import source.kfe.dto.KfeCreateWalletRequest;
import source.kfe.dto.KfeUpdateWalletRequest;
import source.kfe.dto.KfeUtxoResponse;
import source.kfe.dto.KfeWalletNameOption;
import source.kfe.dto.KfeWalletResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/kfe/wallets")
public class KfeWalletController {

    private final FinancialApi financialApi;

    public KfeWalletController(FinancialApi financialApi) {
        this.financialApi = financialApi;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KfeWalletResponse>> create(
            @Valid @RequestBody KfeCreateWalletRequest request,
            Authentication authentication) {
        KfeWalletResponse response = financialApi.createWallet(KfeAuthenticationSupport.authenticatedUserId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("KFE wallet created.", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<KfeWalletResponse>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallets retrieved.",
                financialApi.wallets(KfeAuthenticationSupport.authenticatedUserId(authentication))));
    }

    @GetMapping("/names")
    public ResponseEntity<ApiResponse<List<KfeWalletNameOption>>> names() {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet names retrieved.",
                financialApi.walletNames()));
    }

    @PatchMapping("/{walletId}")
    public ResponseEntity<ApiResponse<KfeWalletResponse>> update(
            @PathVariable UUID walletId,
            @Valid @RequestBody KfeUpdateWalletRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet updated.",
                financialApi.updateWallet(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId, request)));
    }

    @PostMapping("/{walletId}/archive")
    public ResponseEntity<ApiResponse<KfeWalletResponse>> archive(
            @PathVariable UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet archived.",
                financialApi.archiveWallet(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId)));
    }

    @PostMapping("/{walletId}/addresses/rotate")
    public ResponseEntity<ApiResponse<KfeAddressResponse>> rotateAddress(
            @PathVariable UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet address rotated.",
                financialApi.rotateAddress(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId)));
    }

    @GetMapping("/{walletId}/utxos")
    public ResponseEntity<ApiResponse<List<KfeUtxoResponse>>> listUtxos(
            @PathVariable UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE wallet UTXOs retrieved.",
                financialApi.walletUtxos(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId)));
    }

    @PostMapping("/{walletId}/cold-wallet/psbt")
    public ResponseEntity<ApiResponse<KfeColdWalletPsbtResponse>> createColdWalletPsbt(
            @PathVariable UUID walletId,
            @Valid @RequestBody KfeColdWalletPsbtRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE cold wallet PSBT created.",
                financialApi.createColdWalletPsbt(KfeAuthenticationSupport.authenticatedUserId(authentication), walletId, request)));
    }
}
