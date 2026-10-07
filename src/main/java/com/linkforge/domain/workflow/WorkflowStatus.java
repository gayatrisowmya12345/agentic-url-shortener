package com.linkforge.domain.workflow;

public enum WorkflowStatus {
    CREATED,
    IN_PROGRESS,
    WAITING_FOR_CLARIFICATION,
    PROPOSED,
    WAITING_FOR_APPROVAL,
    APPROVED,
    REJECTED,
    APPLYING,
    VALIDATING,
    COMPLETED,
    BLOCKED,
    ROLLED_BACK,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED || this == CANCELLED
                || this == ROLLED_BACK || this == BLOCKED;
    }
}
