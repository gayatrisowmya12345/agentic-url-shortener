package com.linkforge.domain.workflow.exception;

/**
 * Exception thrown when a requested workflow run ID is not found.
 */
public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String message) {
        super(message);
    }
}
