package com.uav.inspection.blockchain;

import com.uav.inspection.identity.ResourcePermissionService;

import java.util.Objects;

/** Two reusable application identities; a request cannot choose a signer or override its MSP. */
public class InstitutionLedgerClients implements AutoCloseable {
    private final EvidenceLedgerClient owner;
    private final EvidenceLedgerClient operator;
    private final String ownerMspId;
    private final String operatorMspId;

    public InstitutionLedgerClients(EvidenceLedgerClient owner, EvidenceLedgerClient operator,
                                    String ownerMspId, String operatorMspId) {
        this.owner = Objects.requireNonNull(owner);
        this.operator = Objects.requireNonNull(operator);
        if (ownerMspId == null || ownerMspId.isBlank() || operatorMspId == null
                || operatorMspId.isBlank() || ownerMspId.equals(operatorMspId)) {
            throw new IllegalArgumentException("Invalid institution MSP mapping");
        }
        this.ownerMspId = ownerMspId;
        this.operatorMspId = operatorMspId;
    }

    public EvidenceLedgerClient forOrganization(String organizationId) {
        return switch (organizationId) {
            case ResourcePermissionService.OWNER -> owner;
            case ResourcePermissionService.OPERATOR -> operator;
            default -> throw new IllegalArgumentException("Unknown ledger organization");
        };
    }

    public String mspId(String organizationId) {
        return switch (organizationId) {
            case ResourcePermissionService.OWNER -> ownerMspId;
            case ResourcePermissionService.OPERATOR -> operatorMspId;
            default -> throw new IllegalArgumentException("Unknown ledger organization");
        };
    }

    @Override
    public void close() {
        try { owner.close(); }
        finally { operator.close(); }
    }
}
