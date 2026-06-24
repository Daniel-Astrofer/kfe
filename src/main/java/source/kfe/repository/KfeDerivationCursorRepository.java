package source.kfe.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import source.kfe.model.KfeDerivationCursorEntity;

import java.util.Optional;

@Repository
public interface KfeDerivationCursorRepository extends JpaRepository<KfeDerivationCursorEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c
            from KfeDerivationCursorEntity c
            where c.cursorKey = :cursorKey
            """)
    Optional<KfeDerivationCursorEntity> findByCursorKeyForUpdate(@Param("cursorKey") String cursorKey);
}
