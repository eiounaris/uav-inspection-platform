-- T025: durable, reference-aware cleanup intents; original business tables stay unchanged.
-- Object keys are internal persistence only, never included in HTTP views, messages or logs.
CREATE TABLE storage_cleanup_task (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_last_modified DATETIME(3) NOT NULL,
    object_size BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_action_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_storage_cleanup_key UNIQUE (object_key_hash),
    KEY ix_storage_cleanup_due (status,next_action_at,id),
    KEY ix_storage_cleanup_lease (status,lease_until,id),
    KEY ix_storage_cleanup_upload (upload_id,id),
    CONSTRAINT ck_storage_cleanup_key_hash CHECK (
        REGEXP_LIKE(object_key_hash,'^[0-9a-f]{64}$','c') AND CHAR_LENGTH(object_key_hash)=64
    ),
    CONSTRAINT ck_storage_cleanup_upload CHECK (
        REGEXP_LIKE(upload_id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$','c')
    ),
    CONSTRAINT ck_storage_cleanup_key_scope CHECK (
        object_key LIKE CONCAT('upload-parts/',upload_id,'/%')
        OR object_key LIKE CONCAT('evidence-files/',upload_id,'/%')
    ),
    CONSTRAINT ck_storage_cleanup_size CHECK (object_size>=0),
    CONSTRAINT ck_storage_cleanup_attempts CHECK (attempt_count>=0),
    CONSTRAINT ck_storage_cleanup_status CHECK (status IN ('PENDING','SENDING','DONE','SKIPPED')),
    CONSTRAINT ck_storage_cleanup_lease CHECK (
        (status='SENDING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status IN ('PENDING','DONE','SKIPPED') AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_storage_cleanup_plan CHECK (
        (status='PENDING' AND next_action_at IS NOT NULL)
        OR (status IN ('SENDING','DONE','SKIPPED') AND next_action_at IS NULL)
    ),
    CONSTRAINT ck_storage_cleanup_error CHECK (
        last_error IS NULL OR REGEXP_LIKE(last_error,'^[A-Z][A-Z0-9_]{0,63}$','c')
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- No upload FK: a server-owned orphan can outlive an absent upload row.
-- Eligibility is rechecked under task -> upload -> cleanup-task locks before external deletion.
