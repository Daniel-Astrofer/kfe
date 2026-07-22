package source.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.model.KfeDerivationCursorEntity;
import source.kfe.repository.KfeDerivationCursorRepository;

@Service
public class KfeDerivationCursorService {

    public static final String KFE_BIP84_EXTERNAL = "KFE_BIP84_EXTERNAL";

    private final KfeDerivationCursorRepository repository;

    public KfeDerivationCursorService(KfeDerivationCursorRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int nextIndex(String cursorKey) {
        KfeDerivationCursorEntity cursor = repository.findByCursorKeyForUpdate(cursorKey)
                .orElseGet(() -> newCursor(cursorKey));
        int current = cursor.getLastIssuedIndex() != null ? cursor.getLastIssuedIndex() : -1;
        int next = current + 1;
        cursor.setLastIssuedIndex(next);
        repository.save(cursor);
        return next;
    }

    private KfeDerivationCursorEntity newCursor(String cursorKey) {
        KfeDerivationCursorEntity cursor = new KfeDerivationCursorEntity();
        cursor.setCursorKey(cursorKey);
        cursor.setLastIssuedIndex(-1);
        return cursor;
    }
}
