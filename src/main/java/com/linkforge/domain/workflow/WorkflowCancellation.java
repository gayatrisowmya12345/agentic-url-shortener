package com.linkforge.domain.workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowCancellation(
        String id,
        String workflowId,
        String cancelledBy,
        String reason,
        Instant cancelledAt
) {
    public static WorkflowCancellation of(String workflowId, String cancelledBy, String reason) {
        return new WorkflowCancellation(
                UUID.randomUUID().toString(),
                workflowId,
                cancelledBy != null && !cancelledBy.isBlank() ? cancelledBy.trim() : "operator",
                reason != null && !reason.isBlank() ? reason.trim() : "Safe stop requested by operator",
                Instant.now()
        );
    }
}
