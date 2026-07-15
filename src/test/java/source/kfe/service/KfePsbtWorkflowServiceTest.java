package source.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import source.kfe.dto.KfeSignedPsbtRequest;
import source.kfe.model.KfePsbtWorkflowEntity;
import source.kfe.model.KfePsbtWorkflowStatus;
import source.kfe.rail.BitcoinCoreRpcClient;
import source.kfe.repository.KfePsbtWorkflowRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfePsbtWorkflowServiceTest {

    private final KfePsbtWorkflowRepository workflowRepository = mock(KfePsbtWorkflowRepository.class);
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider = mock(ObjectProvider.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);

    private final KfePsbtWorkflowService service = new KfePsbtWorkflowService(
            workflowRepository,
            bitcoinCoreProvider,
            new ObjectMapper(),
            hashService,
            auditLogService);

    @Test
    void broadcastIsIdempotentWhenWorkflowAlreadyBroadcast() {
        KfePsbtWorkflowEntity workflow = workflow();
        workflow.setStatus(KfePsbtWorkflowStatus.BROADCAST);
        workflow.setBroadcastTxid("txid-existing");
        when(workflowRepository.findByIdAndUserId(workflow.getId(), 7L)).thenReturn(Optional.of(workflow));

        var response = service.broadcast(7L, workflow.getId());

        assertThat(response.status()).isEqualTo(KfePsbtWorkflowStatus.BROADCAST);
        assertThat(response.broadcastTxid()).isEqualTo("txid-existing");
        verify(bitcoinCoreProvider, never()).getIfAvailable();
        verify(workflowRepository, never()).save(workflow);
    }

    @Test
    void broadcastWorkflowRejectsSignedPsbtAttachmentWithoutFinalizingAgain() {
        KfePsbtWorkflowEntity workflow = workflow();
        workflow.setStatus(KfePsbtWorkflowStatus.BROADCAST);
        when(workflowRepository.findByIdAndUserId(workflow.getId(), 7L)).thenReturn(Optional.of(workflow));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.attachSignedPsbt(7L, workflow.getId(), new KfeSignedPsbtRequest("signed-psbt")));

        assertThat(exception).hasMessage("Broadcast PSBT workflows cannot be modified.");
        verify(bitcoinCoreProvider, never()).getIfAvailable();
        verify(workflowRepository, never()).save(workflow);
    }

    private KfePsbtWorkflowEntity workflow() {
        KfePsbtWorkflowEntity workflow = new KfePsbtWorkflowEntity();
        workflow.setUserId(7L);
        workflow.setWalletId(UUID.randomUUID());
        workflow.setPsbt("psbt");
        workflow.setPsbtHash("psbt-hash");
        workflow.setAmountSats(10_000L);
        workflow.setFeeSats(100L);
        workflow.setDestinationAddress("bcrt1qdestination");
        workflow.setInputsJson("[]");
        return workflow;
    }
}
