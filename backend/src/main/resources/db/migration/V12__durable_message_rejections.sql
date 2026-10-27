-- T022: invalid durable envelopes stop retrying; broker rejects retain safe metadata first.
-- Existing rows and all earlier migrations remain unchanged.
ALTER TABLE outbox_event
    DROP CHECK ck_outbox_event_status,
    DROP CHECK ck_outbox_event_lease,
    DROP CHECK ck_outbox_event_sent_at,
    ADD CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    ADD CONSTRAINT ck_outbox_event_lease CHECK (
        (status = 'SENDING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status IN ('PENDING', 'SENT', 'FAILED') AND lease_token IS NULL AND lease_until IS NULL)
    ),
    ADD CONSTRAINT ck_outbox_event_sent_at CHECK (
        (status = 'SENT' AND sent_at IS NOT NULL)
        OR (status IN ('PENDING', 'SENDING', 'FAILED') AND sent_at IS NULL)
    ),
    ADD CONSTRAINT ck_outbox_event_failed_reason CHECK (
        status <> 'FAILED' OR (last_error IS NOT NULL AND last_error = 'INVALID_ENVELOPE')
    );

-- No payload, arbitrary header, credentials, exception text or signed URL is retained.
-- No FK to business work: a malformed broker delivery may have no valid business identity.
CREATE TABLE message_rejection (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumer_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body_size BIGINT NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_message_rejection_body UNIQUE (consumer_code, body_sha256),
    KEY ix_message_rejection_created (created_at, id),
    CONSTRAINT ck_message_rejection_consumer CHECK (
        consumer_code IN ('FILE_FINALIZE', 'EVIDENCE_ANCHOR')
    ),
    CONSTRAINT ck_message_rejection_hash CHECK (
        REGEXP_LIKE(body_sha256, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_message_rejection_size CHECK (body_size BETWEEN 0 AND 2147483647),
    CONSTRAINT ck_message_rejection_reason CHECK (
        reason_code IN ('INVALID_ENVELOPE', 'UNKNOWN_EVENT', 'MESSAGE_ID_MISMATCH')
    ),
    CONSTRAINT ck_message_rejection_message_id CHECK (
        message_id IS NULL OR REGEXP_LIKE(message_id,
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
