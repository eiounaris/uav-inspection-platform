-- T016: retain the operator and reason for each running execution replacement.
-- The existing V3 composite keys bind both executions to one subtask and task.
-- No existing business row or column is rewritten by this migration.
CREATE TABLE execution_adjustment (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtask_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    new_execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_execution_adjustment_previous UNIQUE (previous_execution_id),
    CONSTRAINT uq_execution_adjustment_new UNIQUE (new_execution_id),
    KEY ix_execution_adjustment_task (task_id, created_at, id),
    KEY ix_execution_adjustment_subtask (subtask_id, created_at, id),
    KEY ix_execution_adjustment_scope (subtask_id, task_id),
    KEY ix_execution_adjustment_previous_scope (previous_execution_id, subtask_id),
    KEY ix_execution_adjustment_new_scope (new_execution_id, subtask_id),
    KEY ix_execution_adjustment_actor (actor_id, created_at, id),
    CONSTRAINT fk_execution_adjustment_subtask_task FOREIGN KEY (subtask_id, task_id)
        REFERENCES inspection_subtask (id, task_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_execution_adjustment_previous FOREIGN KEY (previous_execution_id, subtask_id)
        REFERENCES task_execution (id, subtask_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_execution_adjustment_new FOREIGN KEY (new_execution_id, subtask_id)
        REFERENCES task_execution (id, subtask_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_execution_adjustment_actor FOREIGN KEY (actor_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_execution_adjustment_distinct CHECK (previous_execution_id <> new_execution_id),
    CONSTRAINT ck_execution_adjustment_reason CHECK (
        CHAR_LENGTH(reason) BETWEEN 1 AND 1000 AND REGEXP_LIKE(reason, '[^[:space:]]')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Each execution has at most one predecessor and successor; A -> B -> C remains valid.
-- Application transactions close/start executions, move the current pointer and append this row/audit together.
-- Fixture cleanup deletes execution_adjustment before executions, subtasks and actor users.
