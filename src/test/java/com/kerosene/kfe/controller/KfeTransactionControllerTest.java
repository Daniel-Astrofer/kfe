package com.kerosene.kfe.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import com.kerosene.kfe.application.financial.FinancialApi;
import com.kerosene.kfe.dto.KfeTransactionResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeTransactionControllerTest {

    private final FinancialApi financialApi = mock(FinancialApi.class);
    private final KfeTransactionController controller = new KfeTransactionController(financialApi);
    private final TestingAuthenticationToken authentication = new TestingAuthenticationToken("42", "credentials");

    @Test
    void listsTransactionsForAuthenticatedUserWithRequestedPage() {
        KfeTransactionResponse transaction = mock(KfeTransactionResponse.class);
        when(financialApi.transactions(eq(42L), eq(2), eq(25), isNull())).thenReturn(List.of(transaction));

        var response = controller.list(2, 25, null, authentication);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).containsExactly(transaction);
        verify(financialApi).transactions(eq(42L), eq(2), eq(25), isNull());
    }

    @Test
    void getsParticipantVisibleTransactionForAuthenticatedUser() {
        UUID transactionId = UUID.randomUUID();
        KfeTransactionResponse transaction = mock(KfeTransactionResponse.class);
        when(financialApi.transaction(42L, transactionId)).thenReturn(transaction);

        var response = controller.get(transactionId, authentication);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isSameAs(transaction);
        verify(financialApi).transaction(42L, transactionId);
    }
}
