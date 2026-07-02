package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.common.service.AddressDerivationService;
import source.kfe.dto.KfeCreatePaymentRequest;
import source.kfe.dto.KfePaymentRequestResponse;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletAddressRole;
import source.kfe.model.KfeWalletAddressStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfePaymentRequestRepository;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KfePaymentRequestService {

    private static final Logger log = LoggerFactory.getLogger(KfePaymentRequestService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PUBLIC_ID_BYTES = 18;
    private static final Pattern EXTENDED_PUBLIC_KEY_PATTERN = Pattern.compile(
            "([xtyzuv]pub[1-9A-HJ-NP-Za-km-z]+)");

    private final KfePaymentRequestRepository paymentRequestRepository;
    private final KfeTransactionRepository transactionRepository;
    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final KfeWalletService walletService;
    private final AddressDerivationService addressDerivationService;
    private final KfeReceiveAddressIssuer receiveAddressIssuer;
    private final KfeAuditLogService auditLogService;
    private final KfeDashboardPublisher dashboardPublisher;

    public KfePaymentRequestService(
            KfePaymentRequestRepository paymentRequestRepository,
            KfeTransactionRepository transactionRepository,
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeWalletService walletService,
            AddressDerivationService addressDerivationService,
            KfeReceiveAddressIssuer receiveAddressIssuer,
            KfeAuditLogService auditLogService,
            KfeDashboardPublisher dashboardPublisher) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.walletService = walletService;
        this.addressDerivationService = addressDerivationService;
        this.receiveAddressIssuer = receiveAddressIssuer;
        this.auditLogService = auditLogService;
        this.dashboardPublisher = dashboardPublisher;
    }

    @Transactional
    public KfePaymentRequestResponse create(Long userId, KfeCreatePaymentRequest request) {
        validateCreateRequest(request);
        KfeWalletEntity wallet = walletRepository.findByIdAndUserId(request.walletId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        requireReceivingWallet(wallet, request);

        KfeWalletAddressEntity address = resolveReceivingAddress(userId, wallet, request);
        KfePaymentRequestEntity paymentRequest = new KfePaymentRequestEntity();
        paymentRequest.setPublicId(generatePublicId());
        paymentRequest.setUserId(userId);
        paymentRequest.setWalletId(wallet.getId());
        paymentRequest.setAddressId(address.getId());
        paymentRequest.setAddress(address.getAddress());
        paymentRequest.setRail(resolveRail(request.rail()));
        paymentRequest.setStatus(KfePaymentRequestStatus.OPEN);
        paymentRequest.setAmountSats(request.amountSats());
        paymentRequest.setDescription(clean(request.description()));
        paymentRequest.setMemo(clean(request.memo()));
        paymentRequest.setPayerHint(clean(request.payerHint()));
        paymentRequest.setExpiresAt(request.expiresAt());
        paymentRequest = paymentRequestRepository.save(paymentRequest);

        auditLogService.record(
                "KFE_PAYMENT_REQUEST_CREATED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of(
                        "paymentRequestId", paymentRequest.getId().toString(),
                        "publicId", paymentRequest.getPublicId(),
                        "walletId", wallet.getId().toString(),
                        "rail", paymentRequest.getRail().name()));
        return toResponse(paymentRequest);
    }

    @Transactional
    public List<KfePaymentRequestResponse> list(Long userId) {
        return paymentRequestRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::expireIfDue)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public KfePaymentRequestResponse get(Long userId, UUID id) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        return toResponse(expireIfDue(paymentRequest));
    }

    @Transactional
    public KfePaymentRequestResponse publicGet(String publicId) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        return toResponse(expireIfDue(paymentRequest));
    }

    @Transactional
    public KfePaymentRequestResponse expire(Long userId, UUID id) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (paymentRequest.getStatus() == KfePaymentRequestStatus.OPEN) {
            paymentRequest.expire();
            paymentRequest = paymentRequestRepository.save(paymentRequest);
            auditStatusChange(paymentRequest, "KFE_PAYMENT_REQUEST_EXPIRED");
        }
        return toResponse(paymentRequest);
    }

    @Transactional
    public KfePaymentRequestResponse hide(Long userId, UUID id) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (paymentRequest.getStatus() != KfePaymentRequestStatus.PAID) {
            paymentRequest.hide();
            paymentRequest = paymentRequestRepository.save(paymentRequest);
            auditStatusChange(paymentRequest, "KFE_PAYMENT_REQUEST_HIDDEN");
        }
        return toResponse(paymentRequest);
    }

    @Transactional
    public KfePaymentRequestResponse cancel(Long userId, UUID id) {
        KfePaymentRequestEntity paymentRequest = paymentRequestRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        if (paymentRequest.getStatus() == KfePaymentRequestStatus.OPEN) {
            paymentRequest.cancel();
            paymentRequest = paymentRequestRepository.save(paymentRequest);
            auditStatusChange(paymentRequest, "KFE_PAYMENT_REQUEST_CANCELLED");
        }
        return toResponse(paymentRequest);
    }

    private KfeWalletAddressEntity resolveReceivingAddress(
            Long userId,
            KfeWalletEntity wallet,
            KfeCreatePaymentRequest request) {
        if (Boolean.TRUE.equals(request.issueFreshAddress())) {
            return issueAddressWithoutRotation(wallet);
        }

        return addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                        wallet.getId(),
                        KfeWalletAddressStatus.ACTIVE)
                .orElseGet(() -> issueAddressWithoutRotation(wallet));
    }

    private KfeWalletAddressEntity issueAddressWithoutRotation(KfeWalletEntity wallet) {
        String xpub = receivingXpub(wallet);
        if (hasText(xpub)) {
            int nextIndex = wallet.getLastDerivedIndex() + 1;
            AddressDerivationService.DerivedAddress derived =
                    addressDerivationService.deriveAddressDetailsFromXpub(xpub, nextIndex);
            wallet.setLastDerivedIndex(nextIndex);
            walletRepository.save(wallet);
            return saveAddress(
                    wallet,
                    derived.address(),
                    "m/84'/0'/0'/0/" + nextIndex,
                    nextIndex,
                    "KFE_PAYMENT_REQUEST_XPUB_DERIVATION");
        }

        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
            throw new IllegalArgumentException("WATCH_ONLY wallets require an XPUB to issue fresh receiving addresses.");
        }

        KfeReceiveAddressIssuer.IssuedAddress issued = receiveAddressIssuer.issue(
                "kfe-payment-request-" + wallet.getId());
        if (issued.derivationIndex() >= 0) {
            wallet.setLastDerivedIndex(issued.derivationIndex());
            walletRepository.save(wallet);
        }
        return saveAddress(
                wallet,
                issued.address(),
                issued.derivationPath(),
                issued.derivationIndex() >= 0 ? issued.derivationIndex() : null,
                issued.providerReference());
    }

    private KfeWalletAddressEntity saveAddress(
            KfeWalletEntity wallet,
            String addressValue,
            String derivationPath,
            Integer derivationIndex,
            String providerReference) {
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(wallet.getId());
        address.setAddress(addressValue);
        address.setAddressRole(KfeWalletAddressRole.RECEIVE);
        address.setStatus(KfeWalletAddressStatus.ACTIVE);
        address.setDerivationPath(derivationPath);
        address.setDerivationIndex(derivationIndex);
        address.setProviderReference(providerReference);
        return addressRepository.save(address);
    }

    private void requireReceivingWallet(KfeWalletEntity wallet, KfeCreatePaymentRequest request) {
        if (wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            throw new IllegalStateException("KFE wallet must be active to create a payment request.");
        }
        if (wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
            return;
        }
        if (hasText(receivingXpub(wallet))) {
            return;
        }
        boolean issueFreshAddress = request != null && Boolean.TRUE.equals(request.issueFreshAddress());
        boolean hasActiveAddress = addressRepository.findTopByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(),
                KfeWalletAddressStatus.ACTIVE).isPresent();
        if (!issueFreshAddress && hasActiveAddress) {
            return;
        }
        if (issueFreshAddress) {
            throw new IllegalArgumentException("WATCH_ONLY wallets require an XPUB to issue fresh payment request addresses.");
        }
        throw new IllegalArgumentException("WATCH_ONLY wallets require an XPUB or active receiving address to create payment requests.");
    }

    private void validateCreateRequest(KfeCreatePaymentRequest request) {
        if (request == null || request.walletId() == null) {
            throw new IllegalArgumentException("KFE wallet id is required.");
        }
        if (request.amountSats() != null && request.amountSats() <= 0) {
            throw new IllegalArgumentException("KFE payment request amount must be positive when provided.");
        }
        if (request.expiresAt() != null && request.expiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("KFE payment request expiration must be in the future.");
        }
        if (request.rail() != null && request.rail() != KfeRail.ONCHAIN) {
            throw new IllegalArgumentException("KFE payment requests currently support ONCHAIN receiving only.");
        }
    }

    private KfeRail resolveRail(KfeRail requested) {
        return requested == null ? KfeRail.ONCHAIN : requested;
    }

    private KfePaymentRequestEntity expireIfDue(KfePaymentRequestEntity paymentRequest) {
        if (paymentRequest.isExpired(LocalDateTime.now()) && findSettlementTransaction(paymentRequest).isEmpty()) {
            paymentRequest.expire();
            return paymentRequestRepository.save(paymentRequest);
        }
        return paymentRequest;
    }

    private void auditStatusChange(KfePaymentRequestEntity paymentRequest, String eventType) {
        auditLogService.record(
                eventType,
                null,
                paymentRequest.getWalletId(),
                null,
                null,
                Map.of(
                        "paymentRequestId", paymentRequest.getId().toString(),
                        "publicId", paymentRequest.getPublicId(),
                        "status", paymentRequest.getStatus().name()));
    }

    private KfePaymentRequestResponse toResponse(KfePaymentRequestEntity entity) {
        KfeTransactionEntity settlementTx = findSettlementTransaction(entity).orElse(null);
        return new KfePaymentRequestResponse(
                entity.getId(),
                entity.getPublicId(),
                entity.getUserId(),
                entity.getWalletId(),
                entity.getAddressId(),
                entity.getAddress(),
                entity.getRail(),
                entity.getStatus(),
                entity.getAmountSats(),
                entity.getDescription(),
                entity.getMemo(),
                entity.getPayerHint(),
                entity.getPaidTransactionId(),
                settlementTx == null ? null : settlementTx.getId(),
                settlementTx == null ? null : settlementTx.getStatus(),
                settlementTx == null ? null : settlementTx.getBlockchainTxid(),
                settlementTx == null ? 0 : settlementTx.getConfirmations(),
                settlementTx == null ? null : settlementTx.getGrossAmountSats(),
                settlementTx == null ? null : settlementTx.getReceiverAmountSats(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private Optional<KfeTransactionEntity> findSettlementTransaction(KfePaymentRequestEntity entity) {
        if (entity.getPaidTransactionId() != null) {
            return transactionRepository.findById(entity.getPaidTransactionId());
        }
        return transactionRepository.findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(
                "payment-request:" + entity.getId() + ":");
    }

    private String generatePublicId() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[PUBLIC_ID_BYTES];
            RANDOM.nextBytes(bytes);
            String candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toLowerCase(Locale.ROOT);
            if (paymentRequestRepository.findByPublicId(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate KFE payment request public id.");
    }

    private String clean(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String receivingXpub(KfeWalletEntity wallet) {
        if (wallet == null) {
            return null;
        }
        if (hasText(wallet.getXpub())) {
            return wallet.getXpub().trim();
        }
        return extractExtendedPublicKey(wallet.getDescriptor());
    }

    private String extractExtendedPublicKey(String descriptor) {
        if (!hasText(descriptor)) {
            return null;
        }
        Matcher matcher = EXTENDED_PUBLIC_KEY_PATTERN.matcher(descriptor);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
