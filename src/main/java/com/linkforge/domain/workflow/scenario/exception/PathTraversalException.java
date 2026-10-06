package com.linkforge.domain.workflow.scenario.exception;

/**
 * Thrown when a repository path attempts directory traversal escaping the approved root.
 */
public class PathTraversalException extends InspectionSecurityException {
    public PathTraversalException(String message) {
        super(message);
    }
}
