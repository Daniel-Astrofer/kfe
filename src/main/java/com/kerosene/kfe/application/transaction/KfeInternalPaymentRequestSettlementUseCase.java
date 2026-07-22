package source.kfe.application.transaction;

import org.springframework.stereotype.Service;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.repository.KfePaymentRequestRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class KfeInternalPaymentRequestSettlementUseCase {

    private final KfePaymentRequestRepository paymentRequestRepository;

    public KfeInternalPaymentRequestSettlementUseCase(
            KfePaymentRequestRepository paymentRequestRepository) {
        this.paymentRequestRepository = paymentRequestRepository;
    }

    public KfePaymentRequestEntity lockAndValidate(KfeSubmitTransactionRequest request) {
        String publicId = clean(request.paymentRequestPublicId());
        if (publicId == null) {
            return null;
        }
        if (request.rail() != KfeRail.INTERNAL || request.direction() != KfeDirection.INTERNAL) {
            throw new IllegalArgumentException("paymentRequestPublicId is only supported for INTERNAL payments.");
        }

        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        // INTERNAL rail PRs and platform LIGHTNING PRs (in-app loopback) settle on the ledger.
        // Pure on-chain PRs still require on-chain detection — not this path.
        if (paymentRequest.getRail() != KfeRail.INTERNAL
                && paymentRequest.getRail() != KfeRail.LIGHTNING) {
            throw new IllegalArgumentException(
                    "KFE payment request rail does not support INTERNAL ledger settlement. "
                            + "Use INTERNAL or LIGHTNING payment requests for in-app payments.");
        }
        if (paymentRequest.getStatus() != KfePaymentRequestStatus.OPEN) {
            throw new IllegalStateException("KFE payment request is no longer open.");
        }
        if (paymentRequest.isExpired(LocalDateTime.now(java.time.ZoneOffset.UTC))) {
            throw new IllegalStateException("KFE payment request has expired.");
        }
        if (request.destinationWalletId() == null
                || !paymentRequest.getWalletId().equals(request.destinationWalletId())) {
            throw new IllegalArgumentException("KFE payment request destination wallet does not match.");
        }
        if (paymentRequest.getAmountSats() != null
                && paymentRequest.getAmountSats().longValue() != request.amountSats()) {
            throw new IllegalArgumentException("KFE payment request amount does not match.");
        }
        return paymentRequest;
    }

    public void markPaid(KfePaymentRequestEntity paymentRequest, KfeTransactionEntity transaction) {
        if (paymentRequest == null) {
            return;
        }
        if (paymentRequest.getStatus() != KfePaymentRequestStatus.OPEN) {
            throw new IllegalStateException("KFE payment request is no longer open.");
        }
        if (transaction.getStatus() != KfeTransactionStatus.SETTLED) {
            throw new IllegalStateException("KFE payment request transaction must be settled before marking it paid.");
        }
        paymentRequest.markPaid(transaction.getId());
        paymentRequestRepository.save(paymentRequest);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
