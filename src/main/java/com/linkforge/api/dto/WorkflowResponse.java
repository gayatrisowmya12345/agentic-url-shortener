package com.linkforge.api.dto;

import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;

import java.time.Instant;
import java.util.List;

public record WorkflowResponse(
        String id,
        String requirement,
        String status,
        String currentStage,
        List<String> acceptanceCriteria,
        List<String> assumptions,
        List<String> unansweredQuestions,
        List<PlannedTask> tasks,
        List<WorkflowEvent> events,
        List<AgentDecision> agentDecisions,
        Instant createdAt,
        Instant updatedAt
) {
    public static WorkflowResponse from(WorkflowRun run) {
        return new WorkflowResponse(
                run.getId(),
                run.getRequirement(),
                run.getStatus().name(),
                run.getCurrentStage().name(),
                run.getAcceptanceCriteria(),
                run.getAssumptions(),
                run.getUnansweredQuestions(),
                run.getTasks(),
                run.getEvents(),
                run.getAgentDecisions(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }
}
