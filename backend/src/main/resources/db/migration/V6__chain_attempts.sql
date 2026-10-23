-- T012: recover the original Fabric transaction and commit request after a lost response.
-- These binary checkpoints contain identity/proposal data. They are internal persistence only:
-- never expose them through business responses, message payloads, logs or verification reports.

CREATE TABLE chain_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT NOT NULL,
    tx_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    phase VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_bytes MEDIUMBLOB NOT NULL,
    commit_bytes MEDIUMBLOB NULL,
    validation_code INT NULL,
    block_number BIGINT NULL,
    last_error VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_chain_attempt_number UNIQUE (submission_id, attempt_no),
    CONSTRAINT uq_chain_attempt_transaction UNIQUE (tx_id),
    KEY ix_chain_attempt_phase (phase, updated_at, id),
    CONSTRAINT fk_chain_attempt_submission FOREIGN KEY (submission_id)
        REFERENCES chain_submission (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_chain_attempt_number CHECK (attempt_no >= 1),
    CONSTRAINT ck_chain_attempt_transaction_id CHECK (
        CHAR_LENGTH(tx_id) = 64 AND REGEXP_LIKE(tx_id, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_chain_attempt_phase CHECK (
        phase IN ('PREPARED', 'SUBMITTING', 'SUBMITTED', 'VALID', 'INVALID', 'UNKNOWN')
    ),
    CONSTRAINT ck_chain_attempt_transaction_bytes CHECK (
        OCTET_LENGTH(transaction_bytes) BETWEEN 1 AND 16777215
    ),
    CONSTRAINT ck_chain_attempt_commit_bytes CHECK (
        commit_bytes IS NULL OR OCTET_LENGTH(commit_bytes) BETWEEN 1 AND 16777215
    ),
    CONSTRAINT ck_chain_attempt_submitted_checkpoint CHECK (
        phase <> 'SUBMITTED' OR commit_bytes IS NOT NULL
    ),
    CONSTRAINT ck_chain_attempt_outcome_pair CHECK (
        (validation_code IS NULL AND block_number IS NULL)
        OR (validation_code IS NOT NULL AND block_number IS NOT NULL)
    ),
    CONSTRAINT ck_chain_attempt_validation_code CHECK (validation_code IS NULL OR validation_code >= 0),
    CONSTRAINT ck_chain_attempt_block_number CHECK (block_number IS NULL OR block_number >= 0),
    CONSTRAINT ck_chain_attempt_valid_outcome CHECK (
        phase <> 'VALID' OR (validation_code IS NOT NULL AND validation_code = 0 AND block_number IS NOT NULL)
    ),
    CONSTRAINT ck_chain_attempt_invalid_outcome CHECK (
        phase <> 'INVALID' OR (validation_code IS NOT NULL AND validation_code > 0 AND block_number IS NOT NULL)
    ),
    CONSTRAINT ck_chain_attempt_last_error CHECK (
        last_error IS NULL OR REGEXP_LIKE(last_error, '^[A-Z][A-Z0-9_]{0,199}$', 'c')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- UNKNOWN may have no commit_bytes: a submit timeout cannot prove rejection or non-submission.
-- A ledger lookup can confirm chain_submission while retaining the observed attempt phase.
-- Probe fixture cleanup must delete chain_attempt before chain_submission.
