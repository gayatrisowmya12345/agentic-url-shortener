package com.linkforge.domain.workflow.scenario;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Validated result of scenario classification.
 */
public record ScenarioClassificationResult(
        Scenario scenario,
        String rationale,
        double confidence,
        List<String> evidenceSignals,
        List<String> clarificationQuestions,
        Map<String, Object> metadata
) {
    public ScenarioClassificationResult {
        evidenceSignals = evidenceSignals != null ? List.copyOf(evidenceSignals) : Collections.emptyList();
        clarificationQuestions = clarificationQuestions != null ? List.copyOf(clarificationQuestions) : Collections.emptyList();
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    public boolean fallbackOccurred() {
        return Boolean.TRUE.equals(metadata.get("fallbackOccurred"));
    }

    public String fallbackReason() {
        Object reason = metadata.get("fallbackReason");
        return reason != null ? reason.toString() : null;
    }

    public static ScenarioClassificationResult greenfield(
            String rationale,
            double confidence,
            List<String> evidenceSignals,
            Map<String, Object> metadata
    ) {
        return new ScenarioClassificationResult(
                Scenario.GREENFIELD,
                rationale,
                confidence,
                evidenceSignals,
                Collections.emptyList(),
                metadata
        );
    }

    public static ScenarioClassificationResult brownfield(
            String rationale,
            double confidence,
            List<String> evidenceSignals,
            Map<String, Object> metadata
    ) {
        return new ScenarioClassificationResult(
                Scenario.BROWNFIELD,
                rationale,
                confidence,
                evidenceSignals,
                Collections.emptyList(),
                metadata
        );
    }

    public static ScenarioClassificationResult ambiguous(
            String rationale,
            double confidence,
            List<String> evidenceSignals,
            List<String> clarificationQuestions,
            Map<String, Object> metadata
    ) {
        return new ScenarioClassificationResult(
                Scenario.AMBIGUOUS,
                rationale,
                confidence,
                evidenceSignals,
                clarificationQuestions,
                metadata
        );
    }
}
