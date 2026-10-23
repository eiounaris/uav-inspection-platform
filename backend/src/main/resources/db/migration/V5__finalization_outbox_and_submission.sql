-- T011: local atomic hand-off from upload completion to immutable evidence and anchoring.
-- File bytes, object keys, credentials and Fabric transaction checkpoints are not messages.
-- DATETIME(3) values use the UTC JDBC session. All counters and leases are database-owned.

CREATE TABLE outbox_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    biz_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dispatch_no INT NOT NULL DEFAULT 0,
    payload JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_send_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    sent_at DATETIME(3) NULL,
    last_error VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    CONSTRAINT uq_outbox_event_dispatch UNIQUE (event_type, biz_id, dispatch_no),
    KEY ix_outbox_event_dispatch (status, next_send_at, event_id),
    KEY ix_outbox_event_lease (status, lease_until, event_id),
    CONSTRAINT ck_outbox_event_type CHECK (
        event_type IN ('FILE_FINALIZE_REQUESTED', 'EVIDENCE_ANCHOR_REQUESTED')
    ),
    CONSTRAINT ck_outbox_event_dispatch_no CHECK (dispatch_no >= 0),
    CONSTRAINT ck_outbox_event_payload CHECK (JSON_TYPE(payload) = 'OBJECT'),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'SENDING', 'SENT')),
    CONSTRAINT ck_outbox_event_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_event_lease CHECK (
        (status = 'SENDING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status IN ('PENDING', 'SENT') AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_outbox_event_sent_at CHECK (
        (status = 'SENT' AND sent_at IS NOT NULL)
        OR (status IN ('PENDING', 'SENDING') AND sent_at IS NULL)
    ),
    CONSTRAINT ck_outbox_event_last_error CHECK (
        last_error IS NULL OR REGEXP_LIKE(last_error, '^[A-Z][A-Z0-9_]{0,199}$', 'c')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Deliberately independent of outbox retention: a broker replay may outlive its source row.
CREATE TABLE consumed_event (
    consumer_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_code, event_id),
    KEY ix_consumed_event_updated (status, updated_at, event_id),
    CONSTRAINT ck_consumed_event_consumer CHECK (
        REGEXP_LIKE(consumer_code, '^[A-Z][A-Z0-9_]{0,63}$', 'c')
    ),
    CONSTRAINT ck_consumed_event_status CHECK (status IN ('PROCESSING', 'DONE'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The referenced fixed version supplies evidenceId/version/hash/org without mutable copies.
-- PENDING means awaiting T012 Fabric processing; publication/ACK never means CONFIRMED.
CREATE TABLE chain_submission (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    confirmed_tx_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    confirmed_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    retryable BOOLEAN NOT NULL DEFAULT TRUE,
    retry_count INT NOT NULL DEFAULT 0,
    next_action_at DATETIME(3) NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    last_error VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_chain_submission_version UNIQUE (version_id),
    KEY ix_chain_submission_action (status, next_action_at, id),
    KEY ix_chain_submission_lease (status, lease_until, id),
    CONSTRAINT fk_chain_submission_version FOREIGN KEY (version_id)
        REFERENCES evidence_version (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_chain_submission_status CHECK (
        status IN ('PENDING', 'SUBMITTED', 'CONFIRMED', 'FAILED', 'UNKNOWN')
    ),
    CONSTRAINT ck_chain_submission_confirmed_tx CHECK (
        confirmed_tx_id IS NULL OR REGEXP_LIKE(confirmed_tx_id, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_chain_submission_confirmation CHECK (
        status <> 'CONFIRMED' OR confirmed_tx_id IS NOT NULL
    ),
    CONSTRAINT ck_chain_submission_retryable CHECK (retryable IN (0, 1)),
    CONSTRAINT ck_chain_submission_retry_count CHECK (retry_count >= 0),
    CONSTRAINT ck_chain_submission_lease_pair CHECK (
        (lease_token IS NULL AND lease_until IS NULL)
        OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)
    ),
    CONSTRAINT ck_chain_submission_last_error CHECK (
        last_error IS NULL OR REGEXP_LIKE(last_error, '^[A-Z][A-Z0-9_]{0,199}$', 'c')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Isolated fixture cleanup: consumed_event/outbox_event/chain_submission before evidence_version.
-- Lease ownership and expiry are rechecked in conditional updates; schema checks guard shape only.
