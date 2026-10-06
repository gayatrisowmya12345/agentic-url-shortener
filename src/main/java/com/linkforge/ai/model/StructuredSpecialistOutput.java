package com.linkforge.ai.model;

import java.util.Collections;
import java.util.List;

public record StructuredSpecialistOutput(
        String summary,
        List<String> recommendations,
        List<String> testIdeas,
        List<String> addressedCriteria
) {
    public StructuredSpecialistOutput {
        recommendations = recommendations != null ? List.copyOf(recommendations) : Collections.emptyList();
        testIdeas = testIdeas != null ? List.copyOf(testIdeas) : Collections.emptyList();
        addressedCriteria = addressedCriteria != null ? List.copyOf(addressedCriteria) : Collections.emptyList();
    }
}
