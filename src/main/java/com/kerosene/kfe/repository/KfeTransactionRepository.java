package com.kerosene.kfe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KfeTransactionRepository extends JpaRepository<KfeTransactionEntity, UUID> {

    Optional<KfeTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    List<KfeTransactionEntity> findByDestinationWalletIdAndProvider(
            UUID destinationWalletId, String provider);

    Optional<KfeTransactionEntity> findByIdAndUserId(UUID id, Long userId);

    @Query("""
            select t from KfeTransactionEntity t
            where t.id = :id
              and (
                    t.userId = :userId
                    or (
                        t.rail = :internalRail
                        and t.direction = :internalDirection
                        and t.destinationWalletId in (
                            select destinationWallet.id from KfeWalletEntity destinationWallet
                            where destinationWallet.userId = :userId
                        )
                    )
              )
            """)
    Optional<KfeTransactionEntity> findParticipantVisibleById(
            @Param("id") UUID id,
            @Param("userId") Long userId,
            @Param("internalRail") KfeRail internalRail,
            @Param("internalDirection") KfeDirection internalDirection);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from KfeTransactionEntity t where t.id = :id")
    Optional<KfeTransactionEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from KfeTransactionEntity t
            where t.providerReference = :providerReference
              and t.status = :status
            """)
    List<KfeTransactionEntity> findByProviderReferenceAndStatusForUpdate(
            @Param("providerReference") String providerReference,
            @Param("status") KfeTransactionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from KfeTransactionEntity t
            where t.providerReference = :providerReference
            order by t.createdAt desc
            """)
    List<KfeTransactionEntity> findByProviderReferenceForUpdate(
            @Param("providerReference") String providerReference);

    @Query("""
            select t from KfeTransactionEntity t
            where t.rail = :rail
              and t.direction = :direction
              and t.status in :statuses
              and t.blockchainTxid is not null
              and t.blockchainTxid <> ''
              and t.confirmations < :maxConfirmations
            order by t.updatedAt asc
            """)
    List<KfeTransactionEntity> findOutboundAwaitingConfirmation(
            @Param("rail") KfeRail rail,
            @Param("direction") KfeDirection direction,
            @Param("statuses") Collection<KfeTransactionStatus> statuses,
            @Param("maxConfirmations") int maxConfirmations,
            Pageable pageable);

    @Query("""
            select t from KfeTransactionEntity t
            where t.rail = :rail
              and t.direction = :direction
              and t.status in :statuses
              and t.confirmationMonitoringActive = true
              and t.blockchainTxid is not null
              and t.blockchainTxid <> ''
            order by t.updatedAt asc
            """)
    List<KfeTransactionEntity> findInboundUnderReorgMonitoring(
            @Param("rail") KfeRail rail,
            @Param("direction") KfeDirection direction,
            @Param("statuses") Collection<KfeTransactionStatus> statuses,
            Pageable pageable);

    List<KfeTransactionEntity> findTop25ByUserIdOrderByCreatedAtDesc(Long userId);

    List<KfeTransactionEntity> findTop200ByUserIdOrderByCreatedAtDesc(Long userId);

    List<KfeTransactionEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @Query("""
            select t from KfeTransactionEntity t
            where t.userId = :userId
               or (
                    t.rail = :internalRail
                    and t.direction = :internalDirection
                    and t.destinationWalletId in (
                        select destinationWallet.id from KfeWalletEntity destinationWallet
                        where destinationWallet.userId = :userId
                    )
               )
            order by t.createdAt desc, t.id desc
            """)
    List<KfeTransactionEntity> findParticipantVisibleByUserId(
            @Param("userId") Long userId,
            @Param("internalRail") KfeRail internalRail,
            @Param("internalDirection") KfeDirection internalDirection,
            Pageable pageable);

    @Query("""
            select t from KfeTransactionEntity t
            where (
                    t.userId = :userId
                    or (
                        t.rail = :internalRail
                        and t.direction = :internalDirection
                        and t.destinationWalletId in (
                            select destinationWallet.id from KfeWalletEntity destinationWallet
                            where destinationWallet.userId = :userId
                        )
                    )
               )
              and t.updatedAt > :since
            order by t.createdAt desc, t.id desc
            """)
    List<KfeTransactionEntity> findParticipantVisibleByUserIdSince(
            @Param("userId") Long userId,
            @Param("internalRail") KfeRail internalRail,
            @Param("internalDirection") KfeDirection internalDirection,
            @Param("since") java.time.LocalDateTime since,
            Pageable pageable);

    Optional<KfeTransactionEntity> findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(String idempotencyKeyPrefix);

    List<KfeTransactionEntity> findByBlockchainTxidAndUserId(String blockchainTxid, Long userId);

    @Query("""
            select t from KfeTransactionEntity t
            where (t.sourceWalletId = :walletId or t.destinationWalletId = :walletId)
              and t.status in :statuses
            """)
    List<KfeTransactionEntity> findByWalletIdAndStatusIn(
            @Param("walletId") UUID walletId,
            @Param("statuses") Collection<KfeTransactionStatus> statuses);
}
