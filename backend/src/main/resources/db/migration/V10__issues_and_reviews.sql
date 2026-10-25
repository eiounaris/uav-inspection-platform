-- T018: append version-bound issues and one immutable review conclusion per version.
-- Existing business rows and historical migrations remain unchanged.
-- DATETIME(3) values use the configured UTC JDBC session, as in the earlier migrations.
ALTER TABLE evidence_version
    ADD CONSTRAINT uq_evidence_version_issue_task UNIQUE (id, task_id);

CREATE TABLE issue (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    description VARCHAR(4000) NOT NULL,
    created_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_issue_task (task_id, created_at, id),
    KEY ix_issue_version (evidence_version_id, created_at, id),
    KEY ix_issue_version_task (evidence_version_id, task_id),
    KEY ix_issue_creator (created_by, created_at, id),
    CONSTRAINT fk_issue_version_task FOREIGN KEY (evidence_version_id, task_id)
        REFERENCES evidence_version (id, task_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_issue_creator FOREIGN KEY (created_by)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_issue_description CHECK (
        CHAR_LENGTH(description) BETWEEN 1 AND 4000 AND REGEXP_LIKE(description, '[^[:space:]]')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE review_record (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NULL,
    reviewer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_review_record_version UNIQUE (evidence_version_id),
    KEY ix_review_record_reviewer (reviewer_id, created_at, id),
    CONSTRAINT fk_review_record_version FOREIGN KEY (evidence_version_id)
        REFERENCES evidence_version (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_review_record_reviewer FOREIGN KEY (reviewer_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_review_record_decision CHECK (decision IN ('APPROVED', 'RETURNED')),
    CONSTRAINT ck_review_record_reason CHECK (
        (decision = 'APPROVED' AND reason IS NULL)
        OR (reason IS NOT NULL AND CHAR_LENGTH(reason) BETWEEN 1 AND 1000
            AND REGEXP_LIKE(reason, '[^[:space:]]'))
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The service atomically changes evidence_version.review_status and inserts its review/audit.
-- Multiple issues may refer to one version; a review conclusion is unique to that exact version.
-- Fixture cleanup deletes issue and review_record before evidence_version or the referenced users.
