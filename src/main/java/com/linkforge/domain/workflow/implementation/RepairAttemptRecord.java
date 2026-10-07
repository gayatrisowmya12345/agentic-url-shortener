package com.linkforge.domain.workflow.implementation;

import java.time.Instant;

/**
 * Persisted record of an individual repair loop attempt containing diagnosis,
 * repair proposal, human approval, execution result, and optional rollback.
 */
public record RepairAttemptRecord(
        int attemptNumber,
        BuildDiagnosisResult diagnosis,
        RepairProposal repairProposal,
        String approvalDecision, // "APPROVED", "REJECTED", "PENDING"
        String approver,
        BuildValidationResult buildValidation,
        RollbackResult rollback,
        String status, // "SUCCEEDED", "FAILED", "REJECTED", "ROLLED_BACK", "AWAITING_APPROVAL"
        Instant attemptedAt
) {
}
