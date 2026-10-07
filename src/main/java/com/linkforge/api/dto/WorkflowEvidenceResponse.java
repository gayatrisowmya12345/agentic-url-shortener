package com.linkforge.api.dto;

import com.linkforge.domain.workflow.release.ReleaseReadinessOutcome;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Complete evidence and traceability response mapping acceptance criteria to planned tasks,
 * specialist agent findings, verification bounds, and release readiness.
 * Local filesystem paths are strictly excluded.
 */
public record WorkflowEvidenceResponse(
        String workflowId,
        String scenario,
        String status,
        String currentStage,
        String planHash,
        boolean planApproved,
        boolean codebaseEvidenceAvailable,
        List<CriterionEvidenceItem> criteriaEvidence,
        List<TaskTraceabilityItem> taskTraceability,
        ExecutionVerificationStatus verificationStatus,
        String eventLinkageStatus,
        Instant generatedAt,
        ReleaseReadinessOutcome releaseReadiness
) {
    public WorkflowEvidenceResponse {
        criteriaEvidence = criteriaEvidence != null ? List.copyOf(criteriaEvidence) : Collections.emptyList();
        taskTraceability = taskTraceability != null ? List.copyOf(taskTraceability) : Collections.emptyList();
    }

    public WorkflowEvidenceResponse(
            String workflowId,
            String scenario,
            String status,
            String currentStage,
            String planHash,
            boolean planApproved,
            boolean codebaseEvidenceAvailable,
            List<CriterionEvidenceItem> criteriaEvidence,
            List<TaskTraceabilityItem> taskTraceability,
            ExecutionVerificationStatus verificationStatus,
            String eventLinkageStatus,
            Instant generatedAt
    ) {
        this(workflowId, scenario, status, currentStage, planHash, planApproved,
                codebaseEvidenceAvailable, criteriaEvidence, taskTraceability,
                verificationStatus, eventLinkageStatus, generatedAt, null);
    }
}
