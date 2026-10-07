package com.linkforge.domain.workflow.implementation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Result of a fixed Maven Wrapper build validation execution in an isolated workspace.
 */
public record BuildValidationResult(
        String command,
        int exitCode,
        long durationMs,
        String output,
        String status,
        Instant validatedAt,
        boolean fullVerification,
        List<TestReportItem> testReports
) {
    public BuildValidationResult {
        testReports = testReports != null ? List.copyOf(testReports) : Collections.emptyList();
    }

    public BuildValidationResult(
            String command,
            int exitCode,
            long durationMs,
            String output,
            String status,
            Instant validatedAt
    ) {
        this(command, exitCode, durationMs, output, status, validatedAt, false, List.of());
    }

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status) && exitCode == 0;
    }

    public int totalTests() {
        return testReports.size();
    }

    public int passedTests() {
        return (int) testReports.stream().filter(TestReportItem::isPassed).count();
    }

    public int failedTests() {
        return (int) testReports.stream().filter(TestReportItem::isFailed).count();
    }
}
