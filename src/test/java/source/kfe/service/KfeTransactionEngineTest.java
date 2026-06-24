package source.kfe.service;

import org.junit.jupiter.api.Test;
import source.kfe.application.transaction.KfeSubmitTransactionUseCase;
import source.kfe.application.transaction.KfeTransactionIdempotencyUseCase;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KfeTransactionEngineTest {

    private final KfeSubmitTransactionUseCase submitTransactionUseCase = mock(KfeSubmitTransactionUseCase.class);
    private final KfeTransactionIdempotencyUseCase idempotencyUseCase = mock(KfeTransactionIdempotencyUseCase.class);
    private final KfeTransactionEngine engine = new KfeTransactionEngine(submitTransactionUseCase, idempotencyUseCase);

    @Test
    void submitDelegatesToSubmitUseCase() {
        Long userId = 123L;
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                UUID.randomUUID(),
                null,
                100_000L,
                1000L,
                "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                "memo",
                "totp-code-123",
                "passkey-json",
                "passphrase"
        );

        engine.submit(userId, request);

        verify(submitTransactionUseCase).submit(userId, request, null);
    }
}
