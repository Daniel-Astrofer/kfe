package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.common.service.AddressDerivationService;
import com.kerosene.kfe.dto.KfeCreatePaymentRequest;
import com.kerosene.kfe.dto.KfePaymentRequestResponse;
import com.kerosene.kfe.model.KfePaymentRequestEntity;
import com.kerosene.kfe.model.KfePaymentRequestStatus;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletAddressRole;
import com.kerosene.kfe.model.KfeWalletAddressStatus;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;
import com.kerosene.kfe.rail.CustodyGateway;
import com.kerosene.kfe.rail.LightningInvoiceGateway;
import com.kerosene.kfe.repository.KfePaymentRequestRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;

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
    private final LightningInvoiceGateway lightningInvoiceGateway;
    private final KfeTransactionCancellationService transactionCancellationService;

    public KfePaymentRequestService(
            KfePaymentRequestRepository paymentRequestRepository,
            KfeTransactionRepository transactionRepository,
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeWalletService walletService,
            AddressDerivationService addressDerivationService,
            KfeReceiveAddressIssuer receiveAddressIssuer,
            KfeAuditLogService auditLogService,
            KfeDashboardPublisher dashboardPublisher,
            @Qualifier("kfeExternalLightningInvoiceGateway")
            LightningInvoiceGateway lightningInvoiceGateway,
            KfeTransactionCancellationService transactionCancellationService) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.walletService = walletService;
        this.addressDerivationService = addressDerivationService;
        this.receiveAddressIssuer = receiveAddressIssuer;
        this.auditLogService = auditLogService;
        this.dashboardPublisher = dashboardPublisher;
        this.lightningInvoiceGateway = lightningInvoiceGateway;
        this.transactionCancellationService = transactionCancellationService;
    }

    @Transactional
    public KfePaymentRequestResponse create(Long userId, KfeCreatePaymentRequest request) {
        validateCreateRequest(request);
        KfeWalletEntity wallet = walletRepository.findByIdAndUserId(request.walletId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        KfeRail rail = resolveRail(request.rail());
        requireReceivingWallet(wallet, request, rail);

        KfeWalletAddressEntity address = rail == KfeRail.ONCHAIN
                ? resolveReceivingAddress(userId, wallet, request)
                : null;
        CustodyGateway.GeneratedLightningInvoice lightningInvoice =
                rail == KfeRail.LIGHTNING ? issueLightningInvoice(userId, wallet, request) : null;

        KfePaymentRequestEntity paymentRequest = new KfePaymentRequestEntity();
        paymentRequest.setPublicId(generatePublicId());
        paymentRequest.setUserId(userId);
        paymentRequest.setWalletId(wallet.getId());
        paymentRequest.setAddressId(address == null ? null : address.getId());
        if (lightningInvoice != null) {
            String hash = lightningInvoice.paymentHash();
            paymentRequest.setAddress(shortLightningAddress(hash));
            paymentRequest.setPaymentRequest(lightningInvoice.paymentRequest());
            paymentRequest.setPaymentHash(hash);
            paymentRequest.setProviderReference(lightningInvoice.providerReference());
            if (request.expiresAt() == null && lightningInvoice.expiresAt() != null) {
                paymentRequest.setExpiresAt(lightningInvoice.expiresAt());
            }
        } else {
            paymentRequest.setAddress(address == null ? internalWalletReference(wallet) : address.getAddress());
        }
        paymentRequest.setRail(rail);
        paymentRequest.setStatus(KfePaymentRequestStatus.OPEN);
        paymentRequest.setAmountSats(request.amountSats());
        paymentRequest.setDescription(clean(request.description()));
        paymentRequest.setMemo(clean(request.memo()));
        paymentRequest.setPayerHint(clean(request.payerHint()));
        if (paymentRequest.getExpiresAt() == null) {
            paymentRequest.setExpiresAt(request.expiresAt());
        }
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
        // Full cancel: LN invoice best-effort, fail related pending txs, dashboard refresh.
        return toResponse(transactionCancellationService.cancelPaymentRequest(userId, id));
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

    private void requireReceivingWallet(
            KfeWalletEntity wallet,
            KfeCreatePaymentRequest request,
            KfeRail rail) {
        if (wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            throw new IllegalStateException("KFE wallet must be active to create a payment request.");
        }
        if (rail == KfeRail.INTERNAL) {
            return;
        }
        if (rail == KfeRail.LIGHTNING) {
            if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
                throw new IllegalArgumentException(
                        "WATCH_ONLY wallets cannot create Lightning payment requests (pooled LN credits spendable wallets).");
            }
            if (!wallet.isSpendable()) {
                throw new IllegalStateException("Wallet is not spendable for Lightning receiving.");
            }
            return;
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
        if (request.expiresAt() != null && request.expiresAt().isBefore(LocalDateTime.now(java.time.ZoneOffset.UTC))) {
            throw new IllegalArgumentException("KFE payment request expiration must be in the future.");
        }
        if (request.rail() != null
                && request.rail() != KfeRail.ONCHAIN
                && request.rail() != KfeRail.INTERNAL
                && request.rail() != KfeRail.LIGHTNING) {
            throw new IllegalArgumentException(
                    "KFE payment requests support INTERNAL, ONCHAIN and LIGHTNING receiving.");
        }
        if (request.rail() == KfeRail.LIGHTNING
                && (request.amountSats() == null || request.amountSats() <= 0L)) {
            throw new IllegalArgumentException("LIGHTNING payment requests require a positive amountSats.");
        }
    }

    private KfeRail resolveRail(KfeRail requested) {
        return requested == null ? KfeRail.ONCHAIN : requested;
    }

    private CustodyGateway.GeneratedLightningInvoice issueLightningInvoice(
            Long userId,
            KfeWalletEntity wallet,
            KfeCreatePaymentRequest request) {
        if (!lightningInvoiceGateway.isLive()) {
            throw new IllegalStateException("Lightning invoice gateway is not live.");
        }
        int expiresInSeconds = 3600;
        if (request.expiresAt() != null) {
            long seconds = Duration.between(LocalDateTime.now(java.time.ZoneOffset.UTC), request.expiresAt()).getSeconds();
            expiresInSeconds = (int) Math.max(60L, Math.min(seconds, 86_400L));
        }
        CustodyGateway.GeneratedLightningInvoice invoice = lightningInvoiceGateway.createLightningInvoice(
                new CustodyGateway.LightningInvoiceCommand(
                        userId,
                        null,
                        wallet.getLabel(),
                        request.amountSats(),
                        firstNonBlank(request.memo(), request.description(), "KFE payment request"),
                        expiresInSeconds));
        if (invoice == null
                || invoice.paymentRequest() == null
                || invoice.paymentRequest().isBlank()) {
            throw new IllegalStateException("Lightning invoice gateway returned an empty payment request.");
        }
        return invoice;
    }

    private static String shortLightningAddress(String paymentHash) {
        if (paymentHash == null || paymentHash.isBlank()) {
            return "lightning:invoice";
        }
        String hash = paymentHash.trim();
        if (hash.length() > 100) {
            hash = hash.substring(0, 100);
        }
        return "ln:" + hash;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String internalWalletReference(KfeWalletEntity wallet) {
        return "kerosene:wallet:" + wallet.getId();
    }

    private KfePaymentRequestEntity expireIfDue(KfePaymentRequestEntity paymentRequest) {
        if (paymentRequest.isExpired(LocalDateTime.now(java.time.ZoneOffset.UTC)) && findSettlementTransaction(paymentRequest).isEmpty()) {
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
                entity.getPaymentRequest(),
                entity.getPaymentHash(),
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
                com.kerosene.kfe.time.Utc.toInstant(entity.getExpiresAt()),
                com.kerosene.kfe.time.Utc.toInstant(entity.getCreatedAt()),
                com.kerosene.kfe.time.Utc.toInstant(entity.getUpdatedAt()));
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
