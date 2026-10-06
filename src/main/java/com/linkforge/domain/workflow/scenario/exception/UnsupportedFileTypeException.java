package com.linkforge.domain.workflow.scenario.exception;

/**
 * Thrown when an inspected repository contains unsupported or dangerous file types (e.g. binaries, executables).
 */
public class UnsupportedFileTypeException extends InspectionSecurityException {
    public UnsupportedFileTypeException(String message) {
        super(message);
    }
}
