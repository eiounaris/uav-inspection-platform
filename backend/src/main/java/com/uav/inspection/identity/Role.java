package com.uav.inspection.identity;

/** The fixed application roles; task membership is a separate resource permission. */
public enum Role {
    ORG_ADMIN,
    OWNER_TASK_ADMIN,
    OWNER_REVIEWER,
    OWNER_DATA_APPROVER,
    OPERATOR_TASK_ADMIN,
    OPERATOR_USER;

    public boolean isAllowedFor(String organizationId) {
        boolean owner = "00000000-0000-4000-8000-000000000001".equals(organizationId);
        boolean operator = "00000000-0000-4000-8000-000000000002".equals(organizationId);
        return switch (this) {
            case ORG_ADMIN -> owner || operator;
            case OWNER_TASK_ADMIN, OWNER_REVIEWER, OWNER_DATA_APPROVER -> owner;
            case OPERATOR_TASK_ADMIN, OPERATOR_USER -> operator;
        };
    }
}
