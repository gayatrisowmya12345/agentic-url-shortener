package com.linkforge.domain.workflow.specialist.exception;

public class MissingDependencyException extends TaskGraphException {
    public MissingDependencyException(String message) {
        super(message);
    }
}
