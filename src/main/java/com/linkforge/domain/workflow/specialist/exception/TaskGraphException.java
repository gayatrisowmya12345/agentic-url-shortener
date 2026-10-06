package com.linkforge.domain.workflow.specialist.exception;

public class TaskGraphException extends RuntimeException {
    public TaskGraphException(String message) {
        super(message);
    }

    public TaskGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
