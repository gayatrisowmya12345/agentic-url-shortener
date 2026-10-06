package com.linkforge.api.dto;

import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;

import java.time.Instant;
import java.util.List;

public record WorkflowResponse(
        String id,
        String requirement,
        String scenario,
        String repositoryPath,
        String repositorySummary,
        RepositoryEvidence repositoryEvidence,
        AgentDecision classificationDecision,
        String status,
        String currentStage,
        List<String> acceptanceCriteria,
        List<String> assumptions,
        List<String> unansweredQuestions,
        List<PlannedTask> tasks,
        List<SpecialistInvocation> specialistInvocations,
        List<WorkflowEvent> events,
        List<AgentDecision> agentDecisions,
        Instant createdAt,
        Instant updatedAt
) {
    public static WorkflowResponse from(WorkflowRun run) {
        String repoSummary = run.getRepositoryEvidence() != null
                ? run.getRepositoryEvidence().summary()
                : null;

        return new WorkflowResponse(
                run.getId(),
                run.getRequirement(),
                run.getScenario() != null ? run.getScenario().name() : null,
                run.getRepositoryPath(),
                repoSummary,
                run.getRepositoryEvidence(),
                run.getClassificationDecision(),
                run.getStatus().name(),
                run.getCurrentStage().name(),
                run.getAcceptanceCriteria(),
                run.getAssumptions(),
                run.getUnansweredQuestions(),
                run.getTasks(),
                run.getSpecialistInvocations(),
                run.getEvents(),
                run.getAgentDecisions(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }
}
