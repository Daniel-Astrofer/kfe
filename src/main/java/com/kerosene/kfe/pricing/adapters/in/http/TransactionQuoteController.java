package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.pricing.application.port.in.QuoteTransactionUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/kfe/transactions")
public class TransactionQuoteController {

    private final QuoteTransactionUseCase quoteTransaction;

    public TransactionQuoteController(QuoteTransactionUseCase quoteTransaction) {
        this.quoteTransaction = quoteTransaction;
    }

    @PostMapping("/quote")
    public ResponseEntity<ApiResponse<TransactionQuoteResponse>> quote(
            @Valid @RequestBody TransactionQuoteRequest request) {
        TransactionQuoteResponse response = TransactionQuoteHttpMapper.toResponse(
                quoteTransaction.quote(TransactionQuoteHttpMapper.toCommand(request)));
        return ResponseEntity.ok(ApiResponse.success("KFE transaction quote calculated.", response));
    }
}
