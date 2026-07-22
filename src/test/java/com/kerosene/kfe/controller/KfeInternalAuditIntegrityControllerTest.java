package source.kfe.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import source.common.financial.FinancialAuditIntegrityPort;
import source.kfe.integration.KfeFinancialAuditIntegrityAdapter;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeInternalAuditIntegrityControllerTest {

    private final KfeFinancialAuditIntegrityAdapter adapter = mock(KfeFinancialAuditIntegrityAdapter.class);
    private final KfeInternalAuditIntegrityController controller = new KfeInternalAuditIntegrityController(
            adapter,
            "credential");

    @Test
    void returnsAuditRootWhenCredentialMatches() {
        when(adapter.root()).thenReturn(new FinancialAuditIntegrityPort.AuditRoot(
                "abc123",
                7L,
                1L,
                7L,
                LocalDateTime.parse("2026-06-24T10:15:30")));

        FinancialAuditIntegrityPort.AuditRoot root = controller.root("credential");

        assertEquals("abc123", root.merkleRoot());
        assertEquals(7L, root.eventCount());
        assertEquals(1L, root.fromSequence());
        assertEquals(7L, root.toSequence());
    }

    @Test
    void rejectsInvalidCredential() {
        assertThrows(ResponseStatusException.class, () -> controller.root("wrong"));
    }
}
