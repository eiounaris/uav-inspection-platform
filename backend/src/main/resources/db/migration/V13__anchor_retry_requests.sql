-- T024: manual rechecks are durable intents, not successful Fabric outcomes.
-- Existing tables, columns, transaction checkpoints and migrations remain unchanged.
CREATE TABLE anchor_retry_request (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    dispatch_no INT NOT NULL,
    baseline_attempt_no INT NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_anchor_retry_request_key UNIQUE (submission_id,requested_by,client_request_id),
    CONSTRAINT uq_anchor_retry_dispatch UNIQUE (submission_id,dispatch_no),
    CONSTRAINT uq_anchor_retry_event UNIQUE (event_id),
    KEY ix_anchor_retry_created (submission_id,created_at,id),
    CONSTRAINT fk_anchor_retry_submission FOREIGN KEY (submission_id)
        REFERENCES chain_submission(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_anchor_retry_requester FOREIGN KEY (requested_by)
        REFERENCES app_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_anchor_retry_event FOREIGN KEY (event_id)
        REFERENCES outbox_event(event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_anchor_retry_request_id CHECK (
        REGEXP_LIKE(client_request_id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$','c')
    ),
    CONSTRAINT ck_anchor_retry_request_hash CHECK (
        REGEXP_LIKE(request_hash,'^[0-9a-f]{64}$','c') AND CHAR_LENGTH(request_hash)=64
    ),
    CONSTRAINT ck_anchor_retry_reason CHECK (
        CHAR_LENGTH(reason) BETWEEN 1 AND 1000 AND REGEXP_LIKE(reason,'[^[:space:]]')
    ),
    CONSTRAINT ck_anchor_retry_dispatch CHECK (dispatch_no>=1),
    CONSTRAINT ck_anchor_retry_baseline CHECK (baseline_attempt_no>=0)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Scope, current role/membership and a lease-free recoverable state are checked
-- under task -> current actor -> version -> submission locks in the application.
