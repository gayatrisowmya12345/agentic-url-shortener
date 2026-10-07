package com.linkforge.domain.workflow.implementation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Persisted record of governed implementation execution containing proposal,
 * applied changes, build validation, rollback outcomes, baseline/workspace fingerprints,
 * and applied diff.
 */
public record GovernedExecutionRecord(
        String executionId,
        String workflowId,
        String status,
        String stage,
        String planHash,
        ImplementationProposal proposal,
        List<AppliedFileChange> appliedChanges,
        BuildValidationResult buildValidation,
        RollbackResult rollback,
        String failureReason,
        Instant startedAt,
        Instant completedAt,
        String executionType,
        Map<String, String> baselineFingerprint,
        Map<String, String> workspaceFingerprint,
        String appliedDiff
) {
    public GovernedExecutionRecord {
        appliedChanges = appliedChanges != null ? List.copyOf(appliedChanges) : Collections.emptyList();
        baselineFingerprint = baselineFingerprint != null ? Map.copyOf(baselineFingerprint) : Collections.emptyMap();
        workspaceFingerprint = workspaceFingerprint != null ? Map.copyOf(workspaceFingerprint) : Collections.emptyMap();
    }

    public GovernedExecutionRecord(
            String executionId,
            String workflowId,
            String status,
            String stage,
            String planHash,
            ImplementationProposal proposal,
            List<AppliedFileChange> appliedChanges,
            BuildValidationResult buildValidation,
            RollbackResult rollback,
            String failureReason,
            Instant startedAt,
            Instant completedAt,
            String executionType
    ) {
        this(executionId, workflowId, status, stage, planHash, proposal, appliedChanges, buildValidation,
                rollback, failureReason, startedAt, completedAt, executionType, Collections.emptyMap(), Collections.emptyMap(), null);
    }

    public GovernedExecutionRecord(
            String executionId,
            String workflowId,
            String status,
            String stage,
            String planHash,
            ImplementationProposal proposal,
            List<AppliedFileChange> appliedChanges,
            BuildValidationResult buildValidation,
            RollbackResult rollback,
            String failureReason,
            Instant startedAt,
            Instant completedAt
    ) {
        this(executionId, workflowId, status, stage, planHash, proposal, appliedChanges, buildValidation,
                rollback, failureReason, startedAt, completedAt, null, Collections.emptyMap(), Collections.emptyMap(), null);
    }
}
