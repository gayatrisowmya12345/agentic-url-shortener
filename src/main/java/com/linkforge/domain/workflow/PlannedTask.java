package com.linkforge.domain.workflow;

import java.util.Collections;
import java.util.List;

public record PlannedTask(
        String taskId,
        String title,
        String description,
        List<String> dependencies,
        String status,
        String specialistRole
) {
    public PlannedTask {
        dependencies = dependencies != null ? Collections.unmodifiableList(dependencies) : Collections.emptyList();
    }

    public PlannedTask(String taskId, String title, String description, List<String> dependencies, String status) {
        this(taskId, title, description, dependencies, status, null);
    }

    public PlannedTask withStatus(String newStatus) {
        return new PlannedTask(taskId, title, description, dependencies, newStatus, specialistRole);
    }
}
