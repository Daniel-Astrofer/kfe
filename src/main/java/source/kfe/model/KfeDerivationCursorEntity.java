package source.kfe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "custodial_derivation_cursors", schema = "financial")
public class KfeDerivationCursorEntity {

    @Id
    @Column(name = "cursor_key", nullable = false, updatable = false, length = 64)
    private String cursorKey;

    @Column(name = "last_issued_index", nullable = false)
    private Integer lastIssuedIndex = -1;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getCursorKey() {
        return cursorKey;
    }

    public void setCursorKey(String cursorKey) {
        this.cursorKey = cursorKey;
    }

    public Integer getLastIssuedIndex() {
        return lastIssuedIndex;
    }

    public void setLastIssuedIndex(Integer lastIssuedIndex) {
        this.lastIssuedIndex = lastIssuedIndex;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
