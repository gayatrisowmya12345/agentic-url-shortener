package com.linkforge.domain.workflow.specialist.exception;

public class TaskGraphCycleException extends TaskGraphException {
    public TaskGraphCycleException(String message) {
        super(message);
    }
}
