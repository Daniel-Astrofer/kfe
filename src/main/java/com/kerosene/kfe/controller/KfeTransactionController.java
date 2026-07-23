package com.kerosene.kfe.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import com.kerosene.kfe.application.financial.FinancialApi;
import com.kerosene.kfe.dto.KfeSubmitTransactionRequest;
import com.kerosene.kfe.dto.KfeTransactionQuoteRequest;
import com.kerosene.kfe.dto.KfeTransactionQuoteResponse;
import com.kerosene.kfe.dto.KfeTransactionResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/kfe/transactions")
public class KfeTransactionController {

    private final FinancialApi financialApi;

    public KfeTransactionController(FinancialApi financialApi) {
        this.financialApi = financialApi;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KfeTransactionResponse>> submit(
            @Valid @RequestBody KfeSubmitTransactionRequest request,
            @RequestHeader(value = "X-Device-Hash", required = false) String deviceHash,
            Authentication authentication) {
        Long userId = KfeAuthenticationSupport.authenticatedUserId(authentication);
        KfeTransactionResponse response = financialApi.submitTransaction(userId, request, deviceHash);
        return ResponseEntity.ok(ApiResponse.success("KFE transaction accepted.", response));
    }

    @PostMapping("/quote")
    public ResponseEntity<ApiResponse<KfeTransactionQuoteResponse>> quote(
            @Valid @RequestBody KfeTransactionQuoteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE transaction quote calculated.",
                financialApi.quoteTransaction(request)));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<ApiResponse<KfeTransactionResponse>> get(
            @PathVariable UUID transactionId,
            Authentication authentication) {
        KfeTransactionResponse response = financialApi.transaction(KfeAuthenticationSupport.authenticatedUserId(authentication), transactionId);
        return ResponseEntity.ok(ApiResponse.success("KFE transaction retrieved.", response));
    }

    /**
     * Cancel open invoice / payment link / abandonable pending transaction from transaction details.
     * Body empty. Response includes updated status ({@code FAILED} + {@code USER_CANCELLED}).
     */
    @PostMapping("/{transactionId}/cancel")
    public ResponseEntity<ApiResponse<KfeTransactionResponse>> cancel(
            @PathVariable UUID transactionId,
            Authentication authentication) {
        KfeTransactionResponse response = financialApi.cancelTransaction(
                KfeAuthenticationSupport.authenticatedUserId(authentication),
                transactionId);
        return ResponseEntity.ok(ApiResponse.success("KFE transaction cancelled.", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<KfeTransactionResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) java.time.Instant since,
            Authentication authentication) {
        List<KfeTransactionResponse> response = financialApi.transactions(
                KfeAuthenticationSupport.authenticatedUserId(authentication),
                page,
                size,
                since);
        return ResponseEntity.ok(ApiResponse.success("KFE transactions retrieved.", response));
    }
}
