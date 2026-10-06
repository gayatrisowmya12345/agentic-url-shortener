package com.linkforge.domain.workflow;

import java.util.Collections;
import java.util.List;

public record PlannedTask(
        String taskId,
        String title,
        String description,
        List<String> dependencies,
        String status
) {
    public PlannedTask {
        dependencies = dependencies != null ? Collections.unmodifiableList(dependencies) : Collections.emptyList();
    }
}
