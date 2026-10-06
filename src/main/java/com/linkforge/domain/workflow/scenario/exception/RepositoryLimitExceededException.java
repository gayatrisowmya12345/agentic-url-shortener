package com.linkforge.domain.workflow.scenario.exception;

/**
 * Thrown when an inspected repository exceeds configured file count or size thresholds.
 */
public class RepositoryLimitExceededException extends InspectionException {
    public RepositoryLimitExceededException(String message) {
        super(message);
    }
}
