package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.GetPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ListPaymentsUseCase;
import com.kerosene.kfe.paymentexecution.application.query.GetPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.query.ListPaymentsQuery;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/kfe/transactions")
public class PaymentExecutionController {

    private final GetPaymentUseCase getPayment;
    private final ListPaymentsUseCase listPayments;
    private final CancelPaymentUseCase cancelPayment;

    public PaymentExecutionController(
            GetPaymentUseCase getPayment,
            ListPaymentsUseCase listPayments,
            CancelPaymentUseCase cancelPayment) {
        this.getPayment = getPayment;
        this.listPayments = listPayments;
        this.cancelPayment = cancelPayment;
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<ApiResponse<PaymentExecutionResponse>> get(
            @PathVariable UUID transactionId,
            Authentication authentication) {
        long userId = AuthenticatedUserResolver.userId(authentication);
        var result = getPayment.get(new GetPaymentQuery(userId, new PaymentExecutionId(transactionId)));
        return ResponseEntity.ok(ApiResponse.success(
                "KFE transaction retrieved.", PaymentExecutionHttpMapper.toResponse(result)));
    }

    @PostMapping("/{transactionId}/cancel")
    public ResponseEntity<ApiResponse<PaymentExecutionResponse>> cancel(
            @PathVariable UUID transactionId,
            Authentication authentication) {
        long userId = AuthenticatedUserResolver.userId(authentication);
        var result = cancelPayment.cancel(new CancelPaymentCommand(
                userId, new PaymentExecutionId(transactionId)));
        return ResponseEntity.ok(ApiResponse.success(
                "KFE transaction cancelled.", PaymentExecutionHttpMapper.toResponse(result)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PaymentExecutionResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Instant since,
            Authentication authentication) {
        long userId = AuthenticatedUserResolver.userId(authentication);
        List<PaymentExecutionResponse> response = listPayments
                .list(new ListPaymentsQuery(userId, page, size, since))
                .stream()
                .map(PaymentExecutionHttpMapper::toResponse)
                .toList();
        return ResponseEntity.ok(ApiResponse.success("KFE transactions retrieved.", response));
    }
}
