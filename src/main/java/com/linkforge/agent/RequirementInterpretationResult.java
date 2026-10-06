package com.linkforge.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record RequirementInterpretationResult(
        boolean clear,
        String decisionSummary,
        String rationale,
        List<String> acceptanceCriteria,
        List<String> unansweredQuestions,
        Map<String, Object> metadata
) {
    public static RequirementInterpretationResult clear(
            String decisionSummary,
            String rationale,
            List<String> acceptanceCriteria,
            Map<String, Object> metadata
    ) {
        return new RequirementInterpretationResult(
                true,
                decisionSummary,
                rationale,
                acceptanceCriteria != null ? List.copyOf(acceptanceCriteria) : Collections.emptyList(),
                Collections.emptyList(),
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap()
        );
    }

    public static RequirementInterpretationResult ambiguous(
            String decisionSummary,
            String rationale,
            List<String> unansweredQuestions,
            Map<String, Object> metadata
    ) {
        return new RequirementInterpretationResult(
                false,
                decisionSummary,
                rationale,
                Collections.emptyList(),
                unansweredQuestions != null ? List.copyOf(unansweredQuestions) : Collections.emptyList(),
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap()
        );
    }
}
