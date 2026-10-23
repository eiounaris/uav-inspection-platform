-- T010: durable upload sessions and private part references.
-- evidence_version is reserved for T011: T010 does not create a completed version.
-- DATETIME(3) values are UTC through the configured JDBC session/type handler.
-- Related IDs are checked together so a valid ID cannot be borrowed from another scope.

ALTER TABLE material_requirement
    ADD CONSTRAINT uq_material_requirement_upload_scope
        UNIQUE (id, evidence_id, task_id, subtask_id);

CREATE TABLE upload_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtask_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requirement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submitter_org_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        DEFAULT '00000000-0000-4000-8000-000000000002',
    actor_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_size BIGINT NOT NULL,
    part_size INT NOT NULL,
    part_count INT NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UPLOADING',
    expires_at DATETIME(3) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_action_at DATETIME(3) NULL,
    last_error VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_upload_session_actor_request UNIQUE (actor_type, actor_id, client_request_id),
    CONSTRAINT uq_upload_session_version_scope UNIQUE (id, requirement_id, execution_id),
    KEY ix_upload_session_requirement (requirement_id, execution_id, created_at, id),
    KEY ix_upload_session_execution_subtask (execution_id, subtask_id),
    KEY ix_upload_session_requirement_scope (requirement_id, evidence_id, task_id, subtask_id),
    KEY ix_upload_session_action (status, next_action_at, id),
    KEY ix_upload_session_expiry (status, expires_at, id),
    CONSTRAINT fk_upload_session_requirement_scope
        FOREIGN KEY (requirement_id, evidence_id, task_id, subtask_id)
        REFERENCES material_requirement (id, evidence_id, task_id, subtask_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_upload_session_execution_scope FOREIGN KEY (execution_id, subtask_id)
        REFERENCES task_execution (id, subtask_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_upload_session_submitter FOREIGN KEY (submitter_org_id)
        REFERENCES organization (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_upload_session_submitter CHECK (
        submitter_org_id = '00000000-0000-4000-8000-000000000002'
    ),
    CONSTRAINT ck_upload_session_actor CHECK (actor_type IN ('USER', 'DEVICE')),
    CONSTRAINT ck_upload_session_request_hash CHECK (
        CHAR_LENGTH(request_hash) = 64 AND REGEXP_LIKE(request_hash, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_upload_session_geometry CHECK (
        expected_size > 0 AND part_size > 0 AND part_count >= 1
        AND part_count = ((expected_size - 1) DIV part_size) + 1
    ),
    CONSTRAINT ck_upload_session_status CHECK (
        status IN ('UPLOADING', 'VERIFYING', 'COMPLETED', 'FAILED', 'EXPIRED')
    ),
    CONSTRAINT ck_upload_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_upload_session_lease_pair CHECK (
        (lease_token IS NULL AND lease_until IS NULL)
        OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)
    ),
    CONSTRAINT ck_upload_session_retry_count CHECK (retry_count >= 0),
    CONSTRAINT ck_upload_session_lock_version CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE upload_part (
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    part_no INT NOT NULL,
    size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (session_id, part_no),
    CONSTRAINT uq_upload_part_object_key UNIQUE (object_key),
    CONSTRAINT fk_upload_part_session FOREIGN KEY (session_id)
        REFERENCES upload_session (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_upload_part_number CHECK (part_no >= 1),
    CONSTRAINT ck_upload_part_size CHECK (size > 0),
    CONSTRAINT ck_upload_part_sha256 CHECK (
        CHAR_LENGTH(sha256) = 64 AND REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Only verified final objects become versions. Processing states remain on upload_session.
-- The service checks the live task/batch, ownership, lease and hashes before this future insert.
CREATE TABLE evidence_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requirement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtask_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    upload_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no INT NOT NULL,
    uploader_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    uploader_drone_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    submitter_org_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        DEFAULT '00000000-0000-4000-8000-000000000002',
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_evidence_version_number UNIQUE (evidence_id, version_no),
    CONSTRAINT uq_evidence_version_upload_session UNIQUE (upload_session_id),
    CONSTRAINT uq_evidence_version_object_key UNIQUE (object_key),
    KEY ix_evidence_version_requirement (requirement_id, execution_id, version_no),
    KEY ix_evidence_version_requirement_scope (requirement_id, evidence_id, task_id, subtask_id),
    KEY ix_evidence_version_execution_subtask (execution_id, subtask_id),
    KEY ix_evidence_version_session_scope (upload_session_id, requirement_id, execution_id),
    KEY ix_evidence_version_uploader_org (uploader_user_id, submitter_org_id),
    CONSTRAINT fk_evidence_version_requirement_scope
        FOREIGN KEY (requirement_id, evidence_id, task_id, subtask_id)
        REFERENCES material_requirement (id, evidence_id, task_id, subtask_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_evidence_version_execution_scope FOREIGN KEY (execution_id, subtask_id)
        REFERENCES task_execution (id, subtask_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_evidence_version_session_scope FOREIGN KEY (upload_session_id, requirement_id, execution_id)
        REFERENCES upload_session (id, requirement_id, execution_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_evidence_version_user_org FOREIGN KEY (uploader_user_id, submitter_org_id)
        REFERENCES app_user (id, org_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_evidence_version_drone FOREIGN KEY (uploader_drone_id)
        REFERENCES drone (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_evidence_version_submitter FOREIGN KEY (submitter_org_id)
        REFERENCES organization (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_evidence_version_uploader CHECK (
        (uploader_user_id IS NOT NULL AND uploader_drone_id IS NULL)
        OR (uploader_user_id IS NULL AND uploader_drone_id IS NOT NULL)
    ),
    CONSTRAINT ck_evidence_version_submitter CHECK (
        submitter_org_id = '00000000-0000-4000-8000-000000000002'
    ),
    CONSTRAINT ck_evidence_version_number CHECK (version_no >= 1),
    CONSTRAINT ck_evidence_version_size CHECK (size > 0),
    CONSTRAINT ck_evidence_version_sha256 CHECK (
        CHAR_LENGTH(sha256) = 64 AND REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_evidence_version_review_status CHECK (review_status IN ('PENDING', 'APPROVED', 'RETURNED')),
    CONSTRAINT ck_evidence_version_lock_version CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Isolated-probe cleanup: versions, parts, sessions, then the V3 task-scope cleanup.
-- actor_id is intentionally polymorphic (USER/DEVICE); the service validates its live identity.
-- Part number/size versus session geometry is checked by the service under the session lock.
-- last_error contains an application-owned safe error code, never raw storage/SQL messages.
