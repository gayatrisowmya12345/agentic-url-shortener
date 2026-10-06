package com.linkforge.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * Model schema for LLM-backed scenario classification output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredScenarioClassification(
        String scenario,
        String summary,
        String rationale,
        Double confidence,
        List<String> evidenceSignals,
        List<String> clarificationQuestions
) {
    public StructuredScenarioClassification {
        evidenceSignals = evidenceSignals != null ? List.copyOf(evidenceSignals) : Collections.emptyList();
        clarificationQuestions = clarificationQuestions != null ? List.copyOf(clarificationQuestions) : Collections.emptyList();
    }
}
