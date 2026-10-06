package com.linkforge.agent;

import com.linkforge.domain.workflow.PlannedTask;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record TaskPlanningResult(
        String planSummary,
        String rationale,
        List<PlannedTask> tasks,
        Map<String, Object> metadata
) {
    public TaskPlanningResult {
        tasks = tasks != null ? List.copyOf(tasks) : Collections.emptyList();
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }
}
