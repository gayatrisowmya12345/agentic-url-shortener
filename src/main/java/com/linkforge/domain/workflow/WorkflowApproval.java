package com.linkforge.domain.workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowApproval(
        String id,
        String workflowId,
        String decision, // "APPROVED", "REJECTED"
        String approver,
        String planHash,
        Instant decidedAt,
        String comments
) {
    public static WorkflowApproval of(String workflowId, String decision, String approver, String planHash, String comments) {
        return new WorkflowApproval(
                UUID.randomUUID().toString(),
                workflowId,
                decision != null ? decision.toUpperCase().trim() : "REJECTED",
                approver != null && !approver.isBlank() ? approver.trim() : "authorized-approver",
                planHash != null ? planHash.trim() : "",
                Instant.now(),
                comments
        );
    }

    public boolean isApproved() {
        return "APPROVED".equalsIgnoreCase(decision);
    }

    public boolean isRejected() {
        return "REJECTED".equalsIgnoreCase(decision);
    }
}
