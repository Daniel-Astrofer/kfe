package source.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeUserStatementEntity;
import source.kfe.repository.KfeUserStatementRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KfeStatementServiceTest {

    @Mock
    private KfeUserStatementRepository statementRepository;

    private KfeStatementService service;

    @BeforeEach
    void setUp() {
        service = new KfeStatementService(statementRepository, new ObjectMapper());
    }

    @Test
    void firstWriteUsesLedgerCreatedAtAsOrderKey() {
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(KfeTransactionStatus.EXECUTING);
        LocalDateTime ledgerCreated = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        // Simulate entity already persisted with fixed createdAt.
        setCreated(tx, ledgerCreated);

        when(statementRepository.findByUserIdAndTransactionId(7L, tx.getId()))
                .thenReturn(Optional.empty());
        when(statementRepository.save(any(KfeUserStatementEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordUserStatement(7L, walletId, tx, Map.of(
                "status", "EXECUTING",
                "displayStatus", "PENDING"));

        ArgumentCaptor<KfeUserStatementEntity> captor =
                ArgumentCaptor.forClass(KfeUserStatementEntity.class);
        verify(statementRepository).save(captor.capture());
        KfeUserStatementEntity saved = captor.getValue();
        assertThat(saved.getCreatedAt()).isEqualTo(ledgerCreated);
        assertThat(saved.getTransactionId()).isEqualTo(tx.getId());
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getDisplayPayloadJson()).contains("EXECUTING");
    }

    @Test
    void secondWriteUpdatesPayloadWithoutChangingCreatedAt() {
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(KfeTransactionStatus.SETTLED);
        LocalDateTime ledgerCreated = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        setCreated(tx, ledgerCreated);

        KfeUserStatementEntity existing = new KfeUserStatementEntity();
        existing.setUserId(7L);
        existing.setTransactionId(tx.getId());
        existing.setWalletId(walletId);
        existing.setCreatedAt(ledgerCreated);
        existing.setUpdatedAt(ledgerCreated);
        existing.setExpiresAt(ledgerCreated.plusHours(24));
        existing.setDisplayPayloadJson("{\"status\":\"EXECUTING\",\"displayStatus\":\"PENDING\"}");

        when(statementRepository.findByUserIdAndTransactionId(7L, tx.getId()))
                .thenReturn(Optional.of(existing));
        when(statementRepository.save(any(KfeUserStatementEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordUserStatement(7L, walletId, tx, Map.of(
                "status", "SETTLED",
                "displayStatus", "CONFIRMED"));

        ArgumentCaptor<KfeUserStatementEntity> captor =
                ArgumentCaptor.forClass(KfeUserStatementEntity.class);
        verify(statementRepository).save(captor.capture());
        KfeUserStatementEntity saved = captor.getValue();
        assertThat(saved.getCreatedAt()).isEqualTo(ledgerCreated);
        assertThat(saved.getDisplayPayloadJson()).contains("SETTLED");
        assertThat(saved.getDisplayPayloadJson()).contains("CONFIRMED");
        assertThat(saved.getUpdatedAt()).isAfter(ledgerCreated.minusSeconds(1));
    }

    @Test
    void ifAbsentSkipsWhenRowExists() {
        KfeTransactionEntity tx = transaction(KfeTransactionStatus.SETTLED);
        when(statementRepository.existsByUserIdAndTransactionId(7L, tx.getId())).thenReturn(true);

        service.recordUserStatementIfAbsent(7L, UUID.randomUUID(), tx, Map.of("status", "SETTLED"));

        verify(statementRepository, never()).save(any());
        verify(statementRepository, times(1)).existsByUserIdAndTransactionId(7L, tx.getId());
    }

    private static KfeTransactionEntity transaction(KfeTransactionStatus status) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(7L);
        tx.setIdempotencyKey("idem-" + UUID.randomUUID());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(status);
        tx.setGrossAmountSats(1000L);
        return tx;
    }

    private static void setCreated(KfeTransactionEntity tx, LocalDateTime createdAt) {
        try {
            var field = KfeTransactionEntity.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(tx, createdAt);
            var updated = KfeTransactionEntity.class.getDeclaredField("updatedAt");
            updated.setAccessible(true);
            updated.set(tx, createdAt);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
