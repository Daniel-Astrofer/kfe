package source.kfe.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.common.exception.ErrorCodes;
import source.common.exception.StructuredPlatformException;
import source.kfe.application.financial.FinancialApi;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.dto.KfeTransactionQuoteRequest;
import source.kfe.dto.KfeTransactionQuoteResponse;
import source.kfe.dto.KfeTransactionResponse;

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
        Long userId = authenticatedUserId(authentication);
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
        KfeTransactionResponse response = financialApi.transaction(authenticatedUserId(authentication), transactionId);
        return ResponseEntity.ok(ApiResponse.success("KFE transaction retrieved.", response));
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || "anonymousUser".equals(authentication.getName())) {
            throw unauthenticated();
        }
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException exception) {
            throw unauthenticated();
        }
    }

    private StructuredPlatformException unauthenticated() {
        return new StructuredPlatformException(
                "Usuário autenticado é obrigatório para operações KFE.",
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_INVALID_CREDENTIALS,
                null);
    }
}
