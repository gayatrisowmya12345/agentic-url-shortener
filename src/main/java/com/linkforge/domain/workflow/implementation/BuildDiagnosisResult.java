package com.linkforge.domain.workflow.implementation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Persisted diagnosis of a non-transient build or test validation failure,
 * identifying the likely cause and affected criteria/files.
 */
public record BuildDiagnosisResult(
        String diagnosisId,
        String likelyCause,
        List<String> affectedFiles,
        List<String> affectedCriteria,
        boolean repairable,
        String recommendedAction,
        Instant diagnosedAt
) {
    public BuildDiagnosisResult {
        affectedFiles = affectedFiles != null ? List.copyOf(affectedFiles) : Collections.emptyList();
        affectedCriteria = affectedCriteria != null ? List.copyOf(affectedCriteria) : Collections.emptyList();
    }
}
