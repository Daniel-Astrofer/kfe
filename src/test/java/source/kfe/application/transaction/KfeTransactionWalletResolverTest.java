package source.kfe.application.transaction;

import org.junit.jupiter.api.Test;
import source.common.financial.FinancialUserDirectoryPort;
import source.kfe.dto.KfeSubmitTransactionRequest;
import source.common.exception.FinancialSelfPaymentException;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeTransactionWalletResolverTest {

    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final FinancialUserDirectoryPort userDirectory = mock(FinancialUserDirectoryPort.class);
    private final KfeTransactionWalletResolver resolver = new KfeTransactionWalletResolver(
            walletRepository,
            addressRepository,
            userDirectory);

    @Test
    void resolvesInternalDestinationUsernameToActiveKfeWalletId() {
        KfeWalletEntity wallet = activeWallet(42L, UUID.randomUUID());
        when(userDirectory.findByUsername("Nycollas"))
                .thenReturn(Optional.of(new FinancialUserDirectoryPort.FinancialUserHandle(42L, "nycollas", true)));
        when(walletRepository.findByUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(wallet));

        KfeSubmitTransactionRequest resolved = resolver.resolveInternalDestinationReference(
                internalRequest(null, "@Nycollas"));

        assertEquals(wallet.getId(), resolved.destinationWalletId());
    }

    @Test
    void rejectsInternalDestinationForInactiveUser() {
        when(userDirectory.findByUsername("disabled"))
                .thenReturn(Optional.of(
                        new FinancialUserDirectoryPort.FinancialUserHandle(42L, "disabled", false)));

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolveInternalDestinationReference(
                        internalRequest(null, "@disabled")));
        verify(walletRepository, never()).findByUserIdOrderByCreatedAtDesc(42L);
    }

    @Test
    void resolvesInternalDestinationUuidReferenceWithoutUsernameLookup() {
        UUID walletId = UUID.randomUUID();

        KfeSubmitTransactionRequest resolved = resolver.resolveInternalDestinationReference(
                internalRequest(null, walletId.toString()));

        assertEquals(walletId, resolved.destinationWalletId());
        verify(userDirectory, never()).findByUsername(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void leavesNonInternalRequestUnchanged() {
        KfeSubmitTransactionRequest request = new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                UUID.randomUUID(),
                null,
                1000L,
                100L,
                "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                "memo");

        KfeSubmitTransactionRequest resolved = resolver.resolveInternalDestinationReference(request);

        assertNull(resolved.destinationWalletId());
        verify(userDirectory, never()).findByUsername(org.mockito.ArgumentMatchers.anyString());
    }


    @Test
    void rejectsInternalWhenSourceAndDestinationAreTheSameWallet() {
        Long userId = 123L;
        UUID walletId = UUID.randomUUID();

        assertThrows(FinancialSelfPaymentException.class, () ->
                resolver.requireNotSelfPayment(
                        userId,
                        internalRequest(walletId, walletId, null)));
    }

    @Test
    void allowsInternalTransferBetweenUsersOwnWallets() {
        Long userId = 123L;
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        when(walletRepository.findById(destinationWalletId))
                .thenReturn(Optional.of(activeWallet(userId, destinationWalletId)));

        // INTERNAL (assegurada) → CUSTODIAL_ONCHAIN of same user must be allowed.
        resolver.requireNotSelfPayment(
                userId,
                internalRequest(sourceWalletId, destinationWalletId, null));
    }

    @Test
    void rejectsOwnSourceWalletAddressOnOutboundOnchainTransaction() {
        Long userId = 123L;
        UUID walletId = UUID.randomUUID();
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(walletId);
        address.setAddress("bcrt1qownedaddress0000000000000000000000000000");
        when(addressRepository.findFirstByAddressIgnoreCase(address.getAddress()))
                .thenReturn(Optional.of(address));
        when(walletRepository.findById(walletId))
                .thenReturn(Optional.of(activeWallet(userId, walletId)));

        assertThrows(FinancialSelfPaymentException.class, () ->
                resolver.requireNotSelfPayment(
                        userId,
                        outboundRequest(walletId, address.getAddress())));
    }

    @Test
    void allowsOutboundToOwnCustodialOnchainAddressFromAnotherWallet() {
        Long userId = 123L;
        UUID sourceWalletId = UUID.randomUUID();
        UUID custodialWalletId = UUID.randomUUID();
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(custodialWalletId);
        address.setAddress("tb1qowncustodialaddress00000000000000000000");
        when(addressRepository.findFirstByAddressIgnoreCase(address.getAddress()))
                .thenReturn(Optional.of(address));
        when(walletRepository.findById(custodialWalletId))
                .thenReturn(Optional.of(custodialWallet(userId, custodialWalletId)));

        resolver.requireNotSelfPayment(
                userId,
                outboundRequest(sourceWalletId, address.getAddress()));
    }

    @Test
    void allowsPlatformAddressOwnedByAnotherUser() {
        Long userId = 123L;
        UUID walletId = UUID.randomUUID();
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(walletId);
        address.setAddress("bcrt1qreceiveraddress000000000000000000000000");
        when(addressRepository.findFirstByAddressIgnoreCase(address.getAddress()))
                .thenReturn(Optional.of(address));
        when(walletRepository.findById(walletId))
                .thenReturn(Optional.of(activeWallet(456L, walletId)));

        resolver.requireNotSelfPayment(userId, outboundRequest(address.getAddress()));
    }

    @Test
    void allowsOutboundToOwnWatchOnlyColdWalletAddress() {
        Long userId = 123L;
        UUID sourceWalletId = UUID.randomUUID();
        UUID coldWalletId = UUID.randomUUID();
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(coldWalletId);
        address.setAddress("tb1qowncoldwalletaddress00000000000000000000");
        when(addressRepository.findFirstByAddressIgnoreCase(address.getAddress()))
                .thenReturn(Optional.of(address));
        when(walletRepository.findById(coldWalletId))
                .thenReturn(Optional.of(watchOnlyWallet(userId, coldWalletId)));

        resolver.requireNotSelfPayment(
                userId,
                outboundRequest(sourceWalletId, address.getAddress()));
    }

    private KfeSubmitTransactionRequest internalRequest(UUID destinationWalletId, String externalReference) {
        return internalRequest(UUID.randomUUID(), destinationWalletId, externalReference);
    }

    private KfeSubmitTransactionRequest internalRequest(
            UUID sourceWalletId,
            UUID destinationWalletId,
            String externalReference) {
        return new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.INTERNAL,
                KfeDirection.INTERNAL,
                sourceWalletId,
                destinationWalletId,
                1000L,
                0L,
                externalReference,
                "memo");
    }

    private KfeSubmitTransactionRequest outboundRequest(String externalReference) {
        return outboundRequest(UUID.randomUUID(), externalReference);
    }

    private KfeSubmitTransactionRequest outboundRequest(UUID sourceWalletId, String externalReference) {
        return new KfeSubmitTransactionRequest(
                "idemp-key",
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                sourceWalletId,
                null,
                1000L,
                100L,
                externalReference,
                "memo");
    }

    private KfeWalletEntity activeWallet(Long userId, UUID walletId) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setUserId(userId);
        wallet.setKind(KfeWalletKind.INTERNAL);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setSpendable(true);
        wallet.setLabel("Principal");
        wallet.setQuorumPolicyHash("policy-hash");
        return wallet;
    }

    private KfeWalletEntity watchOnlyWallet(Long userId, UUID walletId) {
        KfeWalletEntity wallet = activeWallet(userId, walletId);
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        wallet.setSpendable(false);
        wallet.setLabel("Cold");
        return wallet;
    }

    private KfeWalletEntity custodialWallet(Long userId, UUID walletId) {
        KfeWalletEntity wallet = activeWallet(userId, walletId);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setSpendable(true);
        wallet.setLabel("Custodial");
        return wallet;
    }
}
