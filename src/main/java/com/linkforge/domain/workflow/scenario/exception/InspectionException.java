package com.linkforge.domain.workflow.scenario.exception;

/**
 * Base exception for repository inspection failures.
 */
public class InspectionException extends RuntimeException {
    public InspectionException(String message) {
        super(message);
    }

    public InspectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
