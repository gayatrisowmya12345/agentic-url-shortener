package com.linkforge.domain.workflow.implementation;

import java.time.Instant;

/**
 * Result of a fixed Maven Wrapper build validation execution in an isolated workspace.
 */
public record BuildValidationResult(
        String command,
        int exitCode,
        long durationMs,
        String output,
        String status,
        Instant validatedAt
) {
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status) && exitCode == 0;
    }
}
