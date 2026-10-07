package com.linkforge.domain.workflow.implementation;

import java.util.Collections;
import java.util.List;

/**
 * Discovered test case execution result from actual test-run reports (e.g., Surefire XML),
 * including method identity, duration, outcome, and criterion lineage.
 */
public record TestReportItem(
        String testSuite,
        String testName,
        String status, // "PASSED", "FAILED", "ERROR", "SKIPPED"
        long durationMs,
        String failureMessage,
        List<String> criterionLineage
) {
    public TestReportItem {
        criterionLineage = criterionLineage != null ? List.copyOf(criterionLineage) : Collections.emptyList();
    }

    public TestReportItem(
            String testSuite,
            String testName,
            String status,
            long durationMs,
            String failureMessage
    ) {
        this(testSuite, testName, status, durationMs, failureMessage, List.of());
    }

    public boolean isPassed() {
        return "PASSED".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status);
    }

    public String testCase() {
        return testName;
    }
}
