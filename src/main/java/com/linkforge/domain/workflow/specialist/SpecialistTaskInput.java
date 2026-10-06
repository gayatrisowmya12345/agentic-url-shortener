package com.linkforge.domain.workflow.specialist;

import com.linkforge.domain.workflow.scenario.Scenario;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record SpecialistTaskInput(
        String taskId,
        String taskTitle,
        String taskDescription,
        List<String> dependencies,
        Map<String, String> dependencyOutputs,
        String requirement,
        List<String> acceptanceCriteria,
        Scenario scenario,
        Map<String, Object> relevantEvidence
) {
    public SpecialistTaskInput {
        dependencies = dependencies != null ? List.copyOf(dependencies) : Collections.emptyList();
        dependencyOutputs = dependencyOutputs != null ? Map.copyOf(dependencyOutputs) : Collections.emptyMap();
        acceptanceCriteria = acceptanceCriteria != null ? List.copyOf(acceptanceCriteria) : Collections.emptyList();
        relevantEvidence = relevantEvidence != null ? Map.copyOf(relevantEvidence) : Collections.emptyMap();
    }
}
