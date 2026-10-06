package com.linkforge.domain.workflow.specialist.exception;

public class SpecialistExecutionException extends RuntimeException {
    public SpecialistExecutionException(String message) {
        super(message);
    }

    public SpecialistExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
