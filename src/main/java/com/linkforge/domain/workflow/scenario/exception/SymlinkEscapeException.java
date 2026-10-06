package com.linkforge.domain.workflow.scenario.exception;

/**
 * Thrown when a symbolic link in the repository points outside the approved repository boundary.
 */
public class SymlinkEscapeException extends InspectionSecurityException {
    public SymlinkEscapeException(String message) {
        super(message);
    }

    public SymlinkEscapeException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
