-- Continuations are reserved independently before crossing a thread/commit boundary.
-- WAITING cannot be claimed; rollback cancels only work proved not to have started.
ALTER TABLE financial.kfe_maintenance_admissions
    ADD COLUMN parent_admission_id UUID REFERENCES financial.kfe_maintenance_admissions(id),
    DROP CONSTRAINT kfe_maintenance_admissions_state_check,
    DROP CONSTRAINT kfe_maintenance_admissions_check;
ALTER TABLE financial.kfe_maintenance_admissions
    ADD CONSTRAINT kfe_maintenance_admissions_state_check
        CHECK (state IN ('WAITING', 'READY', 'IN_FLIGHT', 'UNCERTAIN', 'COMPLETED', 'CANCELLED')),
    ADD CONSTRAINT kfe_maintenance_admissions_completion_check
        CHECK ((state IN ('COMPLETED', 'CANCELLED')) = (completed_at IS NOT NULL)),
    ADD CONSTRAINT kfe_maintenance_admissions_parent_check
        CHECK ((state NOT IN ('WAITING', 'READY', 'CANCELLED') OR parent_admission_id IS NOT NULL)
            AND (parent_admission_id IS NULL OR parent_admission_id <> id));
CREATE INDEX idx_kfe_maintenance_admissions_parent
    ON financial.kfe_maintenance_admissions (parent_admission_id)
    WHERE parent_admission_id IS NOT NULL;
