package com.linkforge.domain.workflow.scenario.exception;

/**
 * Base security exception for repository boundary violations.
 */
public class InspectionSecurityException extends InspectionException {
    public InspectionSecurityException(String message) {
        super(message);
    }
}
