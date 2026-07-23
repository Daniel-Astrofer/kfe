package source.kfe.application.transaction;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import source.common.exception.ErrorCodes;
import source.common.exception.StructuredPlatformException;
import source.common.financial.FinancialTransactionApprovalPort;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;

import java.util.Map;

@Service
public class KfeTransactionAuthorizationUseCase {

    private final FinancialTransactionApprovalPort transactionApprovalPort;

    public KfeTransactionAuthorizationUseCase(FinancialTransactionApprovalPort transactionApprovalPort) {
        this.transactionApprovalPort = transactionApprovalPort;
    }

    public void authorize(Long userId, KfeSubmitTransactionRequest request, String deviceHash) {
        if (!requiresTransactionalAuthorization(request)) {
            return;
        }

        if (requiresLocalFactorAndAssertion(request)) {
            requireLocalFactor(request);
            transactionApprovalPort.approveLocalFactor(userId, deviceHash, request.appPin());
            transactionApprovalPort.approveCustodyTransfer(userId, request.passkeyAssertionJson());
            return;
        }

        transactionApprovalPort.approveWalletOutbound(
                userId,
                userId,
                request.totpCode(),
                request.passkeyAssertionJson(),
                request.confirmationPassphrase());
    }

    private boolean requiresTransactionalAuthorization(KfeSubmitTransactionRequest request) {
        return request.direction() == KfeDirection.OUTBOUND
                || request.direction() == KfeDirection.INTERNAL
                || request.rail() == KfeRail.INTERNAL;
    }

    private boolean requiresLocalFactorAndAssertion(KfeSubmitTransactionRequest request) {
        return isInternalTransfer(request)
                || (request.rail() == KfeRail.ONCHAIN && request.direction() == KfeDirection.OUTBOUND);
    }

    private boolean isInternalTransfer(KfeSubmitTransactionRequest request) {
        return request.direction() == KfeDirection.INTERNAL || request.rail() == KfeRail.INTERNAL;
    }

    private void requireLocalFactor(KfeSubmitTransactionRequest request) {
        if (!hasText(request.appPin())) {
            throw new StructuredPlatformException(
                    "PIN do aplicativo obrigatorio para transacoes internas KFE e onchain custodial.",
                    HttpStatus.UNAUTHORIZED,
                    ErrorCodes.AUTH_TRANSACTIONAL_AUTH_REQUIRED,
                    Map.of("requiredAllOf", new String[] {"appPin", "passkeyAssertionJson"},
                            "missing", new String[] {"appPin"}));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
