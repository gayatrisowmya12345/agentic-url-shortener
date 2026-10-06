package com.linkforge.domain.workflow;

public enum WorkflowStatus {
    CREATED,
    IN_PROGRESS,
    WAITING_FOR_CLARIFICATION,
    WAITING_FOR_APPROVAL,
    REJECTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED || this == CANCELLED;
    }
}
