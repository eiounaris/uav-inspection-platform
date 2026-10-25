-- T015: durable per-device replay protection and the original accepted report result.
-- Keep the assigned drone and execution together so a valid execution ID cannot be borrowed.
-- Existing execution rows/columns are retained; the extra unique index supports the composite FK.
ALTER TABLE task_execution
    ADD CONSTRAINT uq_task_execution_device_scope UNIQUE (id, drone_id);

CREATE TABLE device_event (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    drone_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    boot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq BIGINT NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    progress INT NULL,
    payload_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    applied_progress INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_device_event_sequence UNIQUE (drone_id, boot_id, seq),
    CONSTRAINT uq_device_event_id UNIQUE (drone_id, event_id),
    KEY ix_device_event_execution (execution_id, received_at, id),
    KEY ix_device_event_execution_device (execution_id, drone_id),
    CONSTRAINT fk_device_event_drone FOREIGN KEY (drone_id)
        REFERENCES drone (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_device_event_execution_device FOREIGN KEY (execution_id, drone_id)
        REFERENCES task_execution (id, drone_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_device_event_boot_id CHECK (
        REGEXP_LIKE(boot_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')
    ),
    CONSTRAINT ck_device_event_event_id CHECK (
        REGEXP_LIKE(event_id, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')
    ),
    CONSTRAINT ck_device_event_sequence CHECK (seq BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_device_event_type CHECK (event_type IN ('HEARTBEAT', 'PROGRESS')),
    CONSTRAINT ck_device_event_progress CHECK (
        (event_type = 'HEARTBEAT' AND progress IS NULL)
        OR (event_type = 'PROGRESS' AND progress IS NOT NULL AND progress BETWEEN 0 AND 100)
    ),
    CONSTRAINT ck_device_event_fingerprint CHECK (
        REGEXP_LIKE(payload_fingerprint, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_device_event_applied_progress CHECK (applied_progress BETWEEN 0 AND 100)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Duplicate requests compare the full canonical fingerprint and return this received_at/applied_progress.
-- Device/boot sequencing is identity-scoped; reusing event_id under another boot is also detected.
-- Fixture cleanup deletes device_event before task_execution and drone. No report credentials are stored.
