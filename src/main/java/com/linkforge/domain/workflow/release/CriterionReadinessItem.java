package com.linkforge.domain.workflow.release;

import java.util.List;

/**
 * Criterion-level verification evaluation for release readiness.
 */
public record CriterionReadinessItem(
        String criterionId,
        String criterionText,
        boolean coveredByPassingTest,
        List<String> passingTestNames,
        String status
) {
    public CriterionReadinessItem {
        passingTestNames = passingTestNames != null ? List.copyOf(passingTestNames) : List.of();
    }
}
