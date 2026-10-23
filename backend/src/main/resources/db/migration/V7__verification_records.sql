-- T013: append the result of reading real bytes and querying the evidence ledger.
-- COPY verifies a request-local file and never creates or replaces an evidence version.
-- Only safe application reason codes are persisted, never provider messages or object keys.

CREATE TABLE verification_record (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actual_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ledger_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actual_size BIGINT NULL,
    ledger_tx_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verified_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_verification_record_version (version_id, verified_at, id),
    KEY ix_verification_record_user (user_id, verified_at, id),
    CONSTRAINT fk_verification_record_version FOREIGN KEY (version_id)
        REFERENCES evidence_version (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_verification_record_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_verification_record_source CHECK (source IN ('STORED', 'COPY')),
    CONSTRAINT ck_verification_record_actual_hash CHECK (
        actual_sha256 IS NULL OR REGEXP_LIKE(actual_sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_verification_record_ledger_hash CHECK (
        ledger_sha256 IS NULL OR REGEXP_LIKE(ledger_sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_verification_record_actual_size CHECK (actual_size IS NULL OR actual_size >= 0),
    CONSTRAINT ck_verification_record_ledger_transaction CHECK (
        ledger_tx_id IS NULL OR REGEXP_LIKE(ledger_tx_id, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_verification_record_result CHECK (
        result IN ('MATCH', 'MISMATCH', 'ANCHOR_UNCONFIRMED', 'FILE_READ_FAILED', 'CHAIN_QUERY_FAILED')
    ),
    CONSTRAINT ck_verification_record_reason CHECK (
        REGEXP_LIKE(reason, '^[A-Z][A-Z0-9_]{0,199}$', 'c')
    ),
    CONSTRAINT ck_verification_record_actual_pair CHECK (
        (actual_sha256 IS NULL AND actual_size IS NULL)
        OR (actual_sha256 IS NOT NULL AND actual_size IS NOT NULL)
    ),
    CONSTRAINT ck_verification_record_ledger_pair CHECK (
        (ledger_sha256 IS NULL AND ledger_tx_id IS NULL)
        OR (ledger_sha256 IS NOT NULL AND ledger_tx_id IS NOT NULL)
    ),
    CONSTRAINT ck_verification_record_match CHECK (
        result <> 'MATCH' OR (actual_sha256 IS NOT NULL AND ledger_sha256 IS NOT NULL
            AND actual_sha256 = ledger_sha256)
    ),
    CONSTRAINT ck_verification_record_mismatch CHECK (
        result <> 'MISMATCH' OR (actual_sha256 IS NOT NULL AND ledger_sha256 IS NOT NULL
            AND actual_sha256 <> ledger_sha256)
    ),
    CONSTRAINT ck_verification_record_read_failure CHECK (
        result <> 'FILE_READ_FAILED' OR (actual_sha256 IS NULL AND actual_size IS NULL
            AND ledger_sha256 IS NULL AND ledger_tx_id IS NULL)
    ),
    CONSTRAINT ck_verification_record_chain_uncertainty CHECK (
        result NOT IN ('ANCHOR_UNCONFIRMED', 'CHAIN_QUERY_FAILED')
        OR (actual_sha256 IS NOT NULL AND actual_size IS NOT NULL
            AND ledger_sha256 IS NULL AND ledger_tx_id IS NULL)
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The service rechecks current identity and version permission before inserting result plus audit.
-- Isolated fixture cleanup deletes verification_record before evidence_version and app_user.
