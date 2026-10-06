package com.linkforge.service.retry;

/**
 * Classification of failures during workflow execution.
 * Only TRANSIENT failures are eligible for retry with bounded exponential backoff.
 */
public enum FailureClassification {
    TRANSIENT,
    NON_RETRYABLE
}
