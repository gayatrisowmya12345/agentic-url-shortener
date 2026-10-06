package com.linkforge.api.dto;

import com.linkforge.domain.workflow.WorkflowEvent;

import java.time.Instant;
import java.util.List;

/**
 * Ordered audit event history of a workflow run.
 */
public record WorkflowHistoryResponse(
        String workflowId,
        String status,
        String currentStage,
        int totalEvents,
        List<WorkflowEvent> events,
        Instant retrievedAt
) {
    public static WorkflowHistoryResponse of(String workflowId, String status, String currentStage, List<WorkflowEvent> events) {
        return new WorkflowHistoryResponse(
                workflowId,
                status,
                currentStage,
                events != null ? events.size() : 0,
                events != null ? events : List.of(),
                Instant.now()
        );
    }
}
