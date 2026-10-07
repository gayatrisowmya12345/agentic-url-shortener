package com.linkforge.domain.workflow.release;

import java.time.Instant;
import java.util.List;

/**
 * Persisted evaluation of release readiness calculated from requirement criteria,
 * change approval, test/build evidence, unresolved risks, and rollback status.
 * Actual deployment remains explicitly NOT_SUPPORTED.
 */
public record ReleaseReadinessOutcome(
        String outcomeHash,
        String status,
        boolean readyForRelease,
        List<CriterionReadinessItem> criteriaReadiness,
        int totalTestsPassed,
        int totalTestsFailed,
        List<String> missingCriteriaCoverage,
        List<String> unresolvedRisks,
        String rollbackStatus,
        String buildStatus,
        String deploymentStatus,
        Instant evaluatedAt,
        ReleaseApprovalRecord approval
) {
    public ReleaseReadinessOutcome {
        criteriaReadiness = criteriaReadiness != null ? List.copyOf(criteriaReadiness) : List.of();
        missingCriteriaCoverage = missingCriteriaCoverage != null ? List.copyOf(missingCriteriaCoverage) : List.of();
        unresolvedRisks = unresolvedRisks != null ? List.copyOf(unresolvedRisks) : List.of();
        deploymentStatus = "NOT_SUPPORTED";
    }

    public ReleaseReadinessOutcome withApproval(ReleaseApprovalRecord approvalRecord, String updatedStatus) {
        return new ReleaseReadinessOutcome(
                this.outcomeHash,
                updatedStatus,
                this.readyForRelease,
                this.criteriaReadiness,
                this.totalTestsPassed,
                this.totalTestsFailed,
                this.missingCriteriaCoverage,
                this.unresolvedRisks,
                this.rollbackStatus,
                this.buildStatus,
                this.deploymentStatus,
                this.evaluatedAt,
                approvalRecord
        );
    }
}
