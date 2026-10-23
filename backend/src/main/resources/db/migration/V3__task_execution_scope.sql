-- T009: subtasks, stable material identifiers and initial execution batches.
-- DATETIME(3) values are UTC through the configured JDBC session/type handler.
-- The current-execution foreign key is added after both sides of the relation exist.

CREATE TABLE inspection_subtask (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(200) NOT NULL,
    current_execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_inspection_subtask_id_task UNIQUE (id, task_id),
    KEY ix_inspection_subtask_task (task_id, created_at, id),
    KEY ix_inspection_subtask_current_execution (current_execution_id, id),
    CONSTRAINT fk_inspection_subtask_task FOREIGN KEY (task_id)
        REFERENCES inspection_task (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_inspection_subtask_lock_version CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE material_requirement (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtask_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    name VARCHAR(200) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    next_version_no INT NOT NULL DEFAULT 1,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_material_requirement_evidence UNIQUE (evidence_id),
    KEY ix_material_requirement_task (task_id, subtask_id, created_at, id),
    KEY ix_material_requirement_subtask_task (subtask_id, task_id),
    CONSTRAINT fk_material_requirement_task FOREIGN KEY (task_id)
        REFERENCES inspection_task (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_material_requirement_subtask_task FOREIGN KEY (subtask_id, task_id)
        REFERENCES inspection_subtask (id, task_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_material_requirement_required CHECK (required IN (0, 1)),
    CONSTRAINT ck_material_requirement_next_version CHECK (next_version_no >= 1),
    CONSTRAINT ck_material_requirement_lock_version CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE task_execution (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtask_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    drone_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY',
    progress INT NOT NULL DEFAULT 0,
    started_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    active_subtask_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('READY', 'RUNNING') THEN subtask_id ELSE NULL END
        ) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uq_task_execution_id_subtask UNIQUE (id, subtask_id),
    CONSTRAINT uq_task_execution_active_subtask UNIQUE (active_subtask_id),
    KEY ix_task_execution_subtask (subtask_id, created_at, id),
    KEY ix_task_execution_drone_status (drone_id, status, id),
    CONSTRAINT fk_task_execution_subtask FOREIGN KEY (subtask_id)
        REFERENCES inspection_subtask (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_task_execution_drone FOREIGN KEY (drone_id)
        REFERENCES drone (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_task_execution_status CHECK (status IN ('READY', 'RUNNING', 'CLOSED')),
    CONSTRAINT ck_task_execution_progress CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT ck_task_execution_timestamps CHECK (
        (status = 'READY' AND started_at IS NULL AND closed_at IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND started_at IS NOT NULL AND closed_at IS NOT NULL
            AND closed_at >= started_at)
    ),
    CONSTRAINT ck_task_execution_lock_version CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE inspection_subtask
    ADD CONSTRAINT fk_inspection_subtask_current_execution
    FOREIGN KEY (current_execution_id, id)
        REFERENCES task_execution (id, subtask_id) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- Explicit cleanup order for isolated probes: null current_execution_id, delete execution
-- batches and requirements, then subtasks. No business relationship cascades on deletion.
