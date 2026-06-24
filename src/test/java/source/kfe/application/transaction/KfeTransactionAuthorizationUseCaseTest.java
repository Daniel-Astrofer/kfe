package source.kfe.application.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import source.common.exception.ErrorCodes;
import source.common.exception.StructuredPlatformException;
import source.common.financial.FinancialTransactionApprovalPort;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class KfeTransactionAuthorizationUseCaseTest {

    @Test
    void testJacksonDeserializesAppPinCorrectly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        String json = """
                {
                  "idempotencyKey": "idemp-key",
                  "rail": "INTERNAL",
                  "direction": "INTERNAL",
                  "sourceWalletId": "61a8bb23-e18e-4f32-8414-9844e7300c14",
                  "destinationWalletId": "34b5cc23-e18e-4f32-8414-9844e7300c25",
                  "amountSats": 10000,
                  "networkFeeSats": 0,
                  "memo": "test",
                  "appPin": "1234"
                }
                """;
        KfeSubmitTransactionRequest request = mapper.readValue(json, KfeSubmitTransactionRequest.class);
        assertEquals("1234", request.appPin());
    }

    private final FinancialTransactionApprovalPort transactionApprovalPort = mock(FinancialTransactionApprovalPort.class);
    private final KfeTransactionAuthorizationUseCase useCase = new KfeTransactionAuthorizationUseCase(
            transactionApprovalPort);

    @Test
    void internalTransferWithoutTransactionalAuthorizationMaterialIsRejectedAsUnauthorized() {
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                UUID.randomUUID(),
                UUID.randomUUID(),
                10_000L,
                0L,
                null,
                "memo",
                null,
                null,
                null);

        StructuredPlatformException exception = assertThrows(
                StructuredPlatformException.class,
                () -> useCase.authorize(123L, request, "device-hash"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals(ErrorCodes.AUTH_TRANSACTIONAL_AUTH_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(transactionApprovalPort);
    }
}
