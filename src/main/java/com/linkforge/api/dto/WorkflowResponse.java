package com.linkforge.api.dto;

import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowCancellation;
import com.linkforge.domain.workflow.WorkflowClarification;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;

import java.time.Instant;
import java.util.List;

public record WorkflowResponse(
        String id,
        String originalRequirement,
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
        String planHash,
        WorkflowApproval approval,
        List<WorkflowClarification> clarifications,
        List<SpecialistInvocation> specialistInvocations,
        List<WorkflowEvent> events,
        List<AgentDecision> agentDecisions,
        Instant createdAt,
        Instant updatedAt,
        WorkflowCancellation cancellation,
        WorkflowSummaryResponse summary
) {
    public static WorkflowResponse from(WorkflowRun run) {
        String repoSummary = run.getRepositoryEvidence() != null
                ? run.getRepositoryEvidence().summary()
                : null;

        return new WorkflowResponse(
                run.getId(),
                run.getOriginalRequirement(),
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
                run.getCurrentPlanHash(),
                run.getApproval(),
                run.getClarificationHistory(),
                run.getSpecialistInvocations(),
                run.getEvents(),
                run.getAgentDecisions(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.getCancellation(),
                WorkflowSummaryResponse.from(run)
        );
    }
}
