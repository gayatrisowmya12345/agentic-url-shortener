package com.linkforge.domain.workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowClarification(
        String id,
        String workflowId,
        String clarificationText,
        String submittedBy,
        Instant submittedAt
) {
    public static WorkflowClarification of(String workflowId, String clarificationText, String submittedBy) {
        return new WorkflowClarification(
                UUID.randomUUID().toString(),
                workflowId,
                clarificationText,
                submittedBy != null && !submittedBy.isBlank() ? submittedBy : "operator",
                Instant.now()
        );
    }
}
