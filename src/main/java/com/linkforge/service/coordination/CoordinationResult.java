package com.linkforge.service.coordination;

import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;

import java.util.Collections;
import java.util.List;

public record CoordinationResult(
        List<SpecialistInvocation> invocations,
        List<PlannedTask> updatedTasks,
        boolean allSuccessful,
        int completedCount,
        int failedCount,
        int skippedCount
) {
    public CoordinationResult {
        invocations = invocations != null ? List.copyOf(invocations) : Collections.emptyList();
        updatedTasks = updatedTasks != null ? List.copyOf(updatedTasks) : Collections.emptyList();
    }
}
