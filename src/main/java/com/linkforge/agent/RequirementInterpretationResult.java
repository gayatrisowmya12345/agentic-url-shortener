package com.linkforge.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record RequirementInterpretationResult(
        boolean clear,
        String decisionSummary,
        String rationale,
        List<String> acceptanceCriteria,
        List<String> assumptions,
        List<String> unansweredQuestions,
        Map<String, Object> metadata
) {
    public RequirementInterpretationResult {
        acceptanceCriteria = acceptanceCriteria != null ? List.copyOf(acceptanceCriteria) : Collections.emptyList();
        assumptions = assumptions != null ? List.copyOf(assumptions) : Collections.emptyList();
        unansweredQuestions = unansweredQuestions != null ? List.copyOf(unansweredQuestions) : Collections.emptyList();
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    public boolean fallbackOccurred() {
        return Boolean.TRUE.equals(metadata.get("fallbackOccurred"));
    }

    public String fallbackReason() {
        Object reason = metadata.get("fallbackReason");
        return reason != null ? reason.toString() : null;
    }

    public static RequirementInterpretationResult clear(
            String decisionSummary,
            String rationale,
            List<String> acceptanceCriteria,
            Map<String, Object> metadata
    ) {
        return clear(decisionSummary, rationale, acceptanceCriteria, Collections.emptyList(), metadata);
    }

    public static RequirementInterpretationResult clear(
            String decisionSummary,
            String rationale,
            List<String> acceptanceCriteria,
            List<String> assumptions,
            Map<String, Object> metadata
    ) {
        return new RequirementInterpretationResult(
                true,
                decisionSummary,
                rationale,
                acceptanceCriteria,
                assumptions,
                Collections.emptyList(),
                metadata
        );
    }

    public static RequirementInterpretationResult ambiguous(
            String decisionSummary,
            String rationale,
            List<String> unansweredQuestions,
            Map<String, Object> metadata
    ) {
        return ambiguous(decisionSummary, rationale, Collections.emptyList(), unansweredQuestions, metadata);
    }

    public static RequirementInterpretationResult ambiguous(
            String decisionSummary,
            String rationale,
            List<String> assumptions,
            List<String> unansweredQuestions,
            Map<String, Object> metadata
    ) {
        return new RequirementInterpretationResult(
                false,
                decisionSummary,
                rationale,
                Collections.emptyList(),
                assumptions,
                unansweredQuestions,
                metadata
        );
    }
}
