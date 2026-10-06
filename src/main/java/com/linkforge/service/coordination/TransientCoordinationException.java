package com.linkforge.service.coordination;

import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;
import com.linkforge.service.retry.TransientWorkflowException;

import java.util.Collections;
import java.util.List;

/**
 * Thrown during specialist task coordination when a transient, recoverable failure occurs.
 * Carries all previously completed task invocations and updated tasks so that retry attempts
 * do not repeat completed side-effects or specialist work.
 */
public class TransientCoordinationException extends TransientWorkflowException {

    private final String failingTaskId;
    private final List<SpecialistInvocation> partialInvocations;
    private final List<PlannedTask> partialTasks;

    public TransientCoordinationException(
            String message,
            Throwable cause,
            String failingTaskId,
            List<SpecialistInvocation> partialInvocations,
            List<PlannedTask> partialTasks
    ) {
        super(message, cause);
        this.failingTaskId = failingTaskId;
        this.partialInvocations = partialInvocations != null ? List.copyOf(partialInvocations) : Collections.emptyList();
        this.partialTasks = partialTasks != null ? List.copyOf(partialTasks) : Collections.emptyList();
    }

    public String getFailingTaskId() {
        return failingTaskId;
    }

    public List<SpecialistInvocation> getPartialInvocations() {
        return partialInvocations;
    }

    public List<PlannedTask> getPartialTasks() {
        return partialTasks;
    }
}
