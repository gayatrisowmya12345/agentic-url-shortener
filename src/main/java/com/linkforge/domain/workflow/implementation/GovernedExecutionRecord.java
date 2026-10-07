package com.linkforge.domain.workflow.implementation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Persisted record of governed implementation execution containing proposal,
 * applied changes, build validation, and rollback outcomes.
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
        Instant completedAt
) {
    public GovernedExecutionRecord {
        appliedChanges = appliedChanges != null ? List.copyOf(appliedChanges) : Collections.emptyList();
    }
}
