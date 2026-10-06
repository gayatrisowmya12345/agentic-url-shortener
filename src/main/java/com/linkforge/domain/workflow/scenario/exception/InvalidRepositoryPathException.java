package com.linkforge.domain.workflow.scenario.exception;

/**
 * Thrown when the supplied repository path is missing, does not exist, or is not a directory.
 */
public class InvalidRepositoryPathException extends InspectionException {
    public InvalidRepositoryPathException(String message) {
        super(message);
    }

    public InvalidRepositoryPathException(String message, Throwable cause) {
        super(message, cause);
    }
}
