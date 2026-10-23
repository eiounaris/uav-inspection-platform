-- T007: identity, task scope and the device required by the first business flow.
-- Connections must use UTC; DATETIME(3) does not carry a time zone itself.
-- Organization IDs are public reference data. Accounts, tasks and device secrets
-- are deliberately excluded from this versioned migration.

CREATE TABLE organization (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(200) NOT NULL,
    msp_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_organization_code UNIQUE (code),
    CONSTRAINT uq_organization_msp UNIQUE (msp_id),
    CONSTRAINT ck_organization_fixed_mapping CHECK (
        (id = '00000000-0000-4000-8000-000000000001'
            AND code = 'OrgA' AND msp_id = 'Org1MSP')
        OR
        (id = '00000000-0000-4000-8000-000000000002'
            AND code = 'OrgB' AND msp_id = 'Org2MSP')
    )
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO organization (id, code, name, msp_id) VALUES
    ('00000000-0000-4000-8000-000000000001', 'OrgA', '园区委托方', 'Org1MSP'),
    ('00000000-0000-4000-8000-000000000002', 'OrgB', '无人机运营商', 'Org2MSP');

CREATE TABLE app_user (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    org_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
    password_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    roles JSON NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    token_version BIGINT NOT NULL DEFAULT 0,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT uq_app_user_id_org UNIQUE (id, org_id),
    KEY ix_app_user_org_enabled (org_id, enabled, id),
    CONSTRAINT fk_app_user_organization FOREIGN KEY (org_id)
        REFERENCES organization (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_app_user_password_bcrypt CHECK (
        CHAR_LENGTH(password_hash) = 60
        AND REGEXP_LIKE(password_hash, '^[$]2[aby][$][0-9]{2}[$][./A-Za-z0-9]{53}$', 'c')
    ),
    CONSTRAINT ck_app_user_roles_schema CHECK (
        JSON_SCHEMA_VALID(
            '{"type":"array","minItems":1,"uniqueItems":true,"items":{"type":"string","enum":["ORG_ADMIN","OWNER_TASK_ADMIN","OWNER_REVIEWER","OWNER_DATA_APPROVER","OPERATOR_TASK_ADMIN","OPERATOR_USER"]}}',
            roles
        )
    ),
    CONSTRAINT ck_app_user_roles_organization CHECK (
        (org_id = '00000000-0000-4000-8000-000000000001'
            AND JSON_CONTAINS(
                '["ORG_ADMIN","OWNER_TASK_ADMIN","OWNER_REVIEWER","OWNER_DATA_APPROVER"]', roles
            ))
        OR
        (org_id = '00000000-0000-4000-8000-000000000002'
            AND JSON_CONTAINS('["ORG_ADMIN","OPERATOR_TASK_ADMIN","OPERATOR_USER"]', roles))
    ),
    CONSTRAINT ck_app_user_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_app_user_token_version CHECK (token_version >= 0),
    CONSTRAINT ck_app_user_lock_version CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inspection_task (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_org_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_org_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(200) NOT NULL,
    scope TEXT NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    created_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_inspection_task_owner_status (owner_org_id, status, created_at, id),
    KEY ix_inspection_task_operator_status (operator_org_id, status, created_at, id),
    KEY ix_inspection_task_creator_owner (created_by, owner_org_id),
    CONSTRAINT fk_inspection_task_owner FOREIGN KEY (owner_org_id)
        REFERENCES organization (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inspection_task_operator FOREIGN KEY (operator_org_id)
        REFERENCES organization (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inspection_task_creator_owner FOREIGN KEY (created_by, owner_org_id)
        REFERENCES app_user (id, org_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_inspection_task_owner CHECK (
        owner_org_id = '00000000-0000-4000-8000-000000000001'
    ),
    CONSTRAINT ck_inspection_task_operator CHECK (
        operator_org_id = '00000000-0000-4000-8000-000000000002'
    ),
    CONSTRAINT ck_inspection_task_status CHECK (
        status IN ('DRAFT', 'ASSIGNED', 'RUNNING', 'WAITING_ACCEPTANCE', 'COMPLETED')
    ),
    CONSTRAINT ck_inspection_task_completion CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    ),
    CONSTRAINT ck_inspection_task_lock_version CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE task_member (
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id, user_id),
    KEY ix_task_member_user_task (user_id, task_id),
    CONSTRAINT fk_task_member_task FOREIGN KEY (task_id)
        REFERENCES inspection_task (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_task_member_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE drone (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_org_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at DATETIME(3) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_drone_code UNIQUE (code),
    KEY ix_drone_operator_enabled (operator_org_id, enabled, id),
    CONSTRAINT fk_drone_operator FOREIGN KEY (operator_org_id)
        REFERENCES organization (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_drone_operator CHECK (
        operator_org_id = '00000000-0000-4000-8000-000000000002'
    ),
    CONSTRAINT ck_drone_token_hash CHECK (
        CHAR_LENGTH(token_hash) = 64 AND REGEXP_LIKE(token_hash, '^[0-9a-f]{64}$', 'c')
    ),
    CONSTRAINT ck_drone_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_drone_lock_version CHECK (lock_version >= 0)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
