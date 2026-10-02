-- Cell maintenance is durable and conservative: lease expiry never resolves
-- uncertain financial work. No signer activation or destructive rollback.
CREATE TABLE financial.kfe_maintenance_control (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('ACTIVE', 'DRAINING')),
    change_id VARCHAR(128),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    changed_at TIMESTAMPTZ NOT NULL,
    operator_id BIGINT,
    reason VARCHAR(512),
    CHECK (mode <> 'DRAINING' OR change_id IS NOT NULL)
);
INSERT INTO financial.kfe_maintenance_control
    (singleton_id, mode, revision, changed_at)
VALUES (1, 'ACTIVE', 0, CURRENT_TIMESTAMP);

CREATE TABLE financial.kfe_maintenance_audit (
    id UUID PRIMARY KEY,
    action VARCHAR(16) NOT NULL CHECK (action IN ('DRAIN', 'RESUME')),
    change_id VARCHAR(128) NOT NULL,
    operator_id BIGINT NOT NULL CHECK (operator_id > 0),
    reason VARCHAR(512) NOT NULL,
    expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0),
    revision BIGINT NOT NULL CHECK (revision > 0),
    from_mode VARCHAR(16) NOT NULL CHECK (from_mode IN ('ACTIVE', 'DRAINING')),
    to_mode VARCHAR(16) NOT NULL CHECK (to_mode IN ('ACTIVE', 'DRAINING')),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (change_id, action),
    UNIQUE (revision)
);

CREATE TABLE financial.kfe_maintenance_admissions (
    id UUID PRIMARY KEY,
    operation VARCHAR(128) NOT NULL,
    admitted_revision BIGINT NOT NULL CHECK (admitted_revision >= 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('IN_FLIGHT', 'UNCERTAIN', 'COMPLETED')),
    admitted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX idx_kfe_maintenance_admissions_unresolved
    ON financial.kfe_maintenance_admissions (state, admitted_at)
    WHERE state <> 'COMPLETED';
