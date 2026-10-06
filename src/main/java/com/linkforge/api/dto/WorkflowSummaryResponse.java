package com.linkforge.api.dto;

import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.specialist.SpecialistCriteriaMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * High-level summary of a workflow run derived strictly from persisted records.
 */
public record WorkflowSummaryResponse(
        String workflowId,
        String status,
        String currentStage,
        int requirementRevision,
        String originalRequirement,
        String currentRequirement,
        String scenario,
        int taskCount,
        int completedTaskCount,
        int agentDecisionCount,
        int eventCount,
        int clarificationCount,
        boolean planApproved,
        String planHash,
        boolean cancelled,
        EvidenceCompleteness completeness,
        Instant createdAt,
        Instant updatedAt
) {
    public static WorkflowSummaryResponse from(WorkflowRun run) {
        int revision = 1 + run.getClarificationHistory().size();
        int taskCount = run.getTasks().size();
        int completedTasks = (int) run.getTasks().stream()
                .filter(t -> "COMPLETED".equalsIgnoreCase(t.status()))
                .count();
        boolean approved = run.getApproval() != null && "APPROVED".equalsIgnoreCase(run.getApproval().decision());

        Set<String> addressedCriteria = run.getSpecialistInvocations().stream()
                .flatMap(inv -> inv.addressedCriteria().stream())
                .collect(Collectors.toSet());

        Map<String, String> criteriaMap = SpecialistCriteriaMapper.mapCriteria(run.getAcceptanceCriteria());
        int totalCriteria = criteriaMap.size();
        int addressedCount = (int) criteriaMap.keySet().stream()
                .filter(addressedCriteria::contains)
                .count();

        // Count only actual persisted criterion-to-task links (no min(taskCount, criterionCount) estimation)
        int plannedCount = (int) criteriaMap.keySet().stream()
                .filter(id -> addressedCriteria.contains(id) || run.getTasks().stream().anyMatch(t ->
                        (t.description() != null && t.description().contains(id)) ||
                        (t.title() != null && t.title().contains(id))
                ))
                .count();

        double coverage = totalCriteria > 0 ? (addressedCount * 100.0) / totalCriteria : 0.0;
        boolean allExecuted = taskCount > 0 && completedTasks == taskCount;
        boolean hasCodebaseEvidence = run.getRepositoryEvidence() != null && run.getRepositoryEvidence().hasEvidence();

        EvidenceCompleteness completeness = new EvidenceCompleteness(
                totalCriteria,
                addressedCount,
                plannedCount,
                Math.round(coverage * 100.0) / 100.0,
                hasCodebaseEvidence,
                allExecuted,
                !allExecuted || addressedCount < totalCriteria,
                List.of(
                        "SOURCE_CODE_GENERATION",
                        "BUILD_EXECUTION",
                        "AUTOMATED_TEST_RUNS",
                        "DEPLOYMENT"
                )
        );

        return new WorkflowSummaryResponse(
                run.getId(),
                run.getStatus().name(),
                run.getCurrentStage().name(),
                revision,
                run.getOriginalRequirement(),
                run.getRequirement(),
                run.getScenario() != null ? run.getScenario().name() : null,
                taskCount,
                completedTasks,
                run.getAgentDecisions().size(),
                run.getEvents().size(),
                run.getClarificationHistory().size(),
                approved,
                run.getCurrentPlanHash(),
                run.isCancelled(),
                completeness,
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }

    public static String extractCriterionId(String criterion) {
        if (criterion == null || criterion.isBlank()) return "";
        return SpecialistCriteriaMapper.extractOrAssignId(criterion, 0);
    }
}
