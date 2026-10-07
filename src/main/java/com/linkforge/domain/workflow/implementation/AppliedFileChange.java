package com.linkforge.domain.workflow.implementation;

import java.time.Instant;

/**
 * Record of an applied file change within an isolated workspace.
 */
public record AppliedFileChange(
        String path,
        FileChangeOperation operation,
        String originalHash,
        String newHash,
        Instant appliedAt
) {}
