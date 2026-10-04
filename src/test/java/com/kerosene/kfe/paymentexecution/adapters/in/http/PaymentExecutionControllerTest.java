package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.GetPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ListPaymentsUseCase;
import com.kerosene.kfe.paymentexecution.application.query.GetPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.query.ListPaymentsQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentExecutionControllerTest {

    private final GetPaymentUseCase getPayment = mock(GetPaymentUseCase.class);
    private final ListPaymentsUseCase listPayments = mock(ListPaymentsUseCase.class);
    private final CancelPaymentUseCase cancelPayment = mock(CancelPaymentUseCase.class);
    private final PaymentExecutionController controller =
            new PaymentExecutionController(getPayment, listPayments, cancelPayment);
    private final TestingAuthenticationToken authentication =
            new TestingAuthenticationToken("42", "credentials");

    @Test
    void getsParticipantVisiblePaymentThroughInboundPort() {
        UUID id = UUID.randomUUID();
        PaymentExecutionResult result = result(id);
        when(getPayment.get(any())).thenReturn(result);

        var response = controller.get(id, authentication);

        ArgumentCaptor<GetPaymentQuery> query = ArgumentCaptor.forClass(GetPaymentQuery.class);
        verify(getPayment).get(query.capture());
        assertThat(query.getValue().userId()).isEqualTo(42L);
        assertThat(query.getValue().paymentExecutionId().value()).isEqualTo(id);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().id()).isEqualTo(id);
    }

    @Test
    void listsPaymentsWithIncrementalSyncParameters() {
        Instant since = Instant.parse("2026-09-08T12:00:00Z");
        PaymentExecutionResult result = result(UUID.randomUUID());
        when(listPayments.list(any())).thenReturn(List.of(result));

        var response = controller.list(2, 25, since, authentication);

        ArgumentCaptor<ListPaymentsQuery> query = ArgumentCaptor.forClass(ListPaymentsQuery.class);
        verify(listPayments).list(query.capture());
        assertThat(query.getValue()).isEqualTo(new ListPaymentsQuery(42L, 2, 25, since));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).extracting(PaymentExecutionResponse::id)
                .containsExactly(result.id());
    }

    @Test
    void cancelsPaymentThroughInboundPort() {
        UUID id = UUID.randomUUID();
        when(cancelPayment.cancel(any())).thenReturn(result(id));

        controller.cancel(id, authentication);

        ArgumentCaptor<CancelPaymentCommand> command = ArgumentCaptor.forClass(CancelPaymentCommand.class);
        verify(cancelPayment).cancel(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(42L);
        assertThat(command.getValue().paymentExecutionId().value()).isEqualTo(id);
    }

    private static PaymentExecutionResult result(UUID id) {
        return new PaymentExecutionResult(
                id, ExecutionStatus.INTENT, "PENDING", "PENDING",
                PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                null, UUID.randomUUID(), null,
                "wallet", "source", null, "external",
                100_000L, 100_000L, 1_000L, 900L, 101_900L,
                null, null, null, null, null, null,
                null, 0, "BITCOIN_CORE", null, "tb1", "memo",
                null, null, 0, null, null,
                Instant.parse("2026-09-08T12:00:00Z"),
                Instant.parse("2026-09-08T12:00:00Z"),
                false, null, null, null, null, "OPEN", "PENDING", "RESERVED");
    }
}
