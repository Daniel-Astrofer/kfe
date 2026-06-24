package source.kfe.application.transaction;

import org.springframework.stereotype.Service;
import source.common.financial.FinancialUserDirectoryPort;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.common.exception.FinancialSelfPaymentException;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.UUID;

@Service
public class KfeTransactionWalletResolver {

    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final FinancialUserDirectoryPort userDirectory;

    public KfeTransactionWalletResolver(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            FinancialUserDirectoryPort userDirectory) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.userDirectory = userDirectory;
    }

    public KfeSubmitTransactionRequest resolveInternalDestinationReference(KfeSubmitTransactionRequest request) {
        if (request.direction() != KfeDirection.INTERNAL || request.destinationWalletId() != null) {
            return request;
        }
        String reference = normalizeDestinationReference(request.externalReference());
        if (reference == null) {
            return request;
        }
        UUID walletId = parseUuid(reference);
        if (walletId != null) {
            return request.withDestinationWalletId(walletId);
        }

        FinancialUserDirectoryPort.FinancialUserHandle user = userDirectory.findByUsername(reference)
                .orElseThrow(() -> new IllegalArgumentException("Destination user not found."));
        KfeWalletEntity wallet = walletRepository.findByUserIdOrderByCreatedAtDesc(user.id())
                .stream()
                .filter(this::isSpendableActiveWallet)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Destination user has no active KFE wallet."));
        return request.withDestinationWalletId(wallet.getId());
    }

    public void requireNotSelfPayment(Long userId, KfeSubmitTransactionRequest request) {
        if (request.direction() == KfeDirection.INTERNAL || request.rail() == KfeRail.INTERNAL) {
            requireNotInternalSelfPayment(userId, request);
            return;
        }

        if (request.direction() == KfeDirection.OUTBOUND && request.externalReference() != null) {
            requireNotOwnPlatformAddress(userId, request.externalReference());
        }
    }

    private void requireNotInternalSelfPayment(Long userId, KfeSubmitTransactionRequest request) {
        if (request.destinationWalletId() == null) {
            return;
        }
        walletRepository.findById(request.destinationWalletId())
                .filter(wallet -> wallet.getUserId().equals(userId))
                .ifPresent(wallet -> {
                    throw new FinancialSelfPaymentException();
                });
    }

    private void requireNotOwnPlatformAddress(Long userId, String externalReference) {
        String address = externalReference.trim();
        if (address.isEmpty()) {
            return;
        }
        addressRepository.findFirstByAddressIgnoreCase(address)
                .flatMap(addressEntity -> walletRepository.findById(addressEntity.getWalletId()))
                .filter(wallet -> wallet.getUserId().equals(userId))
                .ifPresent(wallet -> {
                    throw new FinancialSelfPaymentException();
                });
    }

    public KfeWalletEntity resolveSourceWallet(Long userId, KfeSubmitTransactionRequest request) {
        if (!requiresSourceReserve(request)) {
            return null;
        }
        if (request.sourceWalletId() == null) {
            throw new IllegalArgumentException("sourceWalletId is required.");
        }
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(request.sourceWalletId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("Source KFE wallet not found."));
        requireSpendable(wallet, "source");
        return wallet;
    }

    public KfeWalletEntity resolveDestinationWallet(Long userId, KfeSubmitTransactionRequest request) {
        if (request.direction() == KfeDirection.OUTBOUND) {
            return null;
        }
        if (request.destinationWalletId() == null) {
            throw new IllegalArgumentException("destinationWalletId is required.");
        }
        KfeWalletEntity wallet = walletRepository.findById(request.destinationWalletId())
                .orElseThrow(() -> new IllegalArgumentException("Destination KFE wallet not found."));
        if (request.direction() == KfeDirection.INBOUND && !wallet.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Inbound destination wallet must belong to the authenticated user.");
        }
        requireSpendable(wallet, "destination");
        return wallet;
    }

    public boolean requiresSourceReserve(KfeSubmitTransactionRequest request) {
        return request.direction() == KfeDirection.OUTBOUND || request.direction() == KfeDirection.INTERNAL;
    }

    private void requireSpendable(KfeWalletEntity wallet, String role) {
        if (wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            throw new IllegalStateException(role + " wallet is not active.");
        }
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY || !wallet.isSpendable()) {
            throw new IllegalStateException(role + " wallet is watch-only and cannot move funds.");
        }
    }

    private boolean isSpendableActiveWallet(KfeWalletEntity wallet) {
        return wallet.getStatus() == KfeWalletStatus.ACTIVE
                && wallet.getKind() != KfeWalletKind.WATCH_ONLY
                && wallet.isSpendable();
    }

    private String normalizeDestinationReference(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.startsWith("@")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
