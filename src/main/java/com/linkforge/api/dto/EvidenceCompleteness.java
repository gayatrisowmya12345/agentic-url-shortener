package com.linkforge.api.dto;

import java.util.List;

/**
 * Metric summary of evidence completeness across acceptance criteria and specialist tasks.
 */
public record EvidenceCompleteness(
        int totalAcceptanceCriteria,
        int criteriaAddressedBySpecialists,
        int criteriaPlannedInTasks,
        double coveragePercentage,
        boolean hasCodebaseEvidence,
        boolean allPlannedTasksExecuted,
        boolean verificationGapsPresent,
        List<String> unverifiedCapabilities
) {}
