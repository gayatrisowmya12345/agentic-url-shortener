package com.linkforge.api.dto;

import com.linkforge.domain.workflow.implementation.AppliedFileChange;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.RollbackResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record GovernedExecutionResponse(
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
    public GovernedExecutionResponse(
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

    public static GovernedExecutionResponse from(GovernedExecutionRecord record) {
        if (record == null) {
            return null;
        }
        return new GovernedExecutionResponse(
                record.executionId(),
                record.workflowId(),
                record.status(),
                record.stage(),
                record.planHash(),
                record.proposal(),
                record.appliedChanges(),
                record.buildValidation(),
                record.rollback(),
                record.failureReason(),
                record.startedAt(),
                record.completedAt(),
                record.executionType(),
                record.baselineFingerprint(),
                record.workspaceFingerprint(),
                record.appliedDiff()
        );
    }
}
