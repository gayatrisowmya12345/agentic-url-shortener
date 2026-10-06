package com.linkforge.service.retry;

/**
 * Exception explicitly designating a transient, recoverable failure during workflow execution.
 * Eligible for bounded exponential backoff retry.
 */
public class TransientWorkflowException extends RuntimeException {

    private final boolean retryable;

    public TransientWorkflowException(String message) {
        super(message);
        this.retryable = true;
    }

    public TransientWorkflowException(String message, Throwable cause) {
        super(message, cause);
        this.retryable = true;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
