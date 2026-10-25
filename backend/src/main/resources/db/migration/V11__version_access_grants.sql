-- T020: one row is both a request and, after approval, a version-specific grant.
-- Expiry is derived from database UTC time; no scheduler needs to rewrite status.
CREATE TABLE access_grant (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    applicant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(1000) NOT NULL,
    requested_valid_until DATETIME(3) NOT NULL,
    approver_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    decision_reason VARCHAR(1000) NULL,
    decided_at DATETIME(3) NULL,
    valid_until DATETIME(3) NULL,
    revoked_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revoke_reason VARCHAR(1000) NULL,
    revoked_at DATETIME(3) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_access_grant_applicant_request UNIQUE (applicant_id,client_request_id),
    KEY ix_access_grant_version_active (version_id,status,valid_until,id),
    KEY ix_access_grant_applicant_version (applicant_id,version_id,status,valid_until,id),
    KEY ix_access_grant_applicant_page (applicant_id,created_at,id),
    KEY ix_access_grant_version_page (version_id,created_at,id),
    CONSTRAINT fk_access_grant_version FOREIGN KEY (version_id)
        REFERENCES evidence_version(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_access_grant_applicant FOREIGN KEY (applicant_id)
        REFERENCES app_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_access_grant_approver FOREIGN KEY (approver_id)
        REFERENCES app_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_access_grant_revoker FOREIGN KEY (revoked_by)
        REFERENCES app_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_access_grant_request_id CHECK (
        REGEXP_LIKE(client_request_id,'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$','c')
    ),
    CONSTRAINT ck_access_grant_request_hash CHECK (
        REGEXP_LIKE(request_hash,'^[0-9a-f]{64}$','c') AND CHAR_LENGTH(request_hash)=64
    ),
    CONSTRAINT ck_access_grant_status CHECK (status IN ('PENDING','APPROVED','REJECTED','REVOKED')),
    CONSTRAINT ck_access_grant_reason CHECK (REGEXP_LIKE(reason,'[^[:space:]]')),
    CONSTRAINT ck_access_grant_decision_reason CHECK (
        decision_reason IS NULL OR REGEXP_LIKE(decision_reason,'[^[:space:]]')
    ),
    CONSTRAINT ck_access_grant_revoke_reason CHECK (
        revoke_reason IS NULL OR REGEXP_LIKE(revoke_reason,'[^[:space:]]')
    ),
    CONSTRAINT ck_access_grant_requested_time CHECK (requested_valid_until>created_at),
    CONSTRAINT ck_access_grant_lock_version CHECK (lock_version>=0),
    CONSTRAINT ck_access_grant_decision_pair CHECK (
        (status='PENDING' AND approver_id IS NULL AND decided_at IS NULL
            AND decision_reason IS NULL AND valid_until IS NULL)
        OR (status='REJECTED' AND approver_id IS NOT NULL AND decided_at IS NOT NULL
            AND decision_reason IS NOT NULL AND valid_until IS NULL)
        OR (status IN ('APPROVED','REVOKED') AND approver_id IS NOT NULL
            AND decided_at IS NOT NULL AND valid_until IS NOT NULL)
    ),
    CONSTRAINT ck_access_grant_decision_time CHECK (decided_at IS NULL OR decided_at>=created_at),
    CONSTRAINT ck_access_grant_valid_time CHECK (
        valid_until IS NULL OR (valid_until>decided_at AND valid_until<=requested_valid_until)
    ),
    CONSTRAINT ck_access_grant_revoke_pair CHECK (
        (status='REVOKED' AND revoked_by IS NOT NULL AND revoke_reason IS NOT NULL AND revoked_at IS NOT NULL)
        OR (status<>'REVOKED' AND revoked_by IS NULL AND revoke_reason IS NULL AND revoked_at IS NULL)
    ),
    CONSTRAINT ck_access_grant_revoke_time CHECK (
        revoked_at IS NULL OR (revoked_at>=decided_at AND revoked_at<valid_until)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Actor organization, live role/membership, version review and confirmed anchor are
-- rechecked by the service under task -> current account -> grant locks.
