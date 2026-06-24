package source.kfe.service;

import org.junit.jupiter.api.Test;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.repository.KfeExecutionOutboxRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeExecutionOutboxServiceTest {

    private final KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
    private final KfeExecutionOutboxService service = new KfeExecutionOutboxService(repository);

    @Test
    void claimsDueOutboxItemsWithNormalizedWorkerId() {
        KfeExecutionOutboxEntity candidate = new KfeExecutionOutboxEntity();
        when(repository.findTop100ClaimCandidates(anyCollection(), any(), any()))
                .thenReturn(List.of(candidate));
        when(repository.claimDue(eq(candidate.getId()), anyCollection(), any(), any(), eq("kfe-worker")))
                .thenReturn(1);
        when(repository.findById(candidate.getId())).thenReturn(Optional.of(candidate));

        List<KfeExecutionOutboxEntity> claimed = service.claimDue("KFE-WORKER");

        assertThat(claimed).containsExactly(candidate);
        verify(repository).claimDue(eq(candidate.getId()), anyCollection(), any(), any(), eq("kfe-worker"));
    }
}
