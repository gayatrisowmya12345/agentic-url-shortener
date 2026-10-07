package com.linkforge.api.dto;

import com.linkforge.domain.workflow.implementation.AppliedFileChange;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.RollbackResult;

import java.time.Instant;
import java.util.List;

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
        Instant completedAt
) {
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
                record.completedAt()
        );
    }
}
