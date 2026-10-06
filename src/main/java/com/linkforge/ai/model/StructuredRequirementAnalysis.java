package com.linkforge.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * Structured schema returned by model-backed requirement analysis.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredRequirementAnalysis(
        String summary,
        List<String> acceptanceCriteria,
        List<String> assumptions,
        List<String> clarificationQuestions
) {
    public StructuredRequirementAnalysis {
        acceptanceCriteria = acceptanceCriteria != null ? List.copyOf(acceptanceCriteria) : Collections.emptyList();
        assumptions = assumptions != null ? List.copyOf(assumptions) : Collections.emptyList();
        clarificationQuestions = clarificationQuestions != null ? List.copyOf(clarificationQuestions) : Collections.emptyList();
    }
}
