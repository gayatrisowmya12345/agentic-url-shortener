package com.linkforge.service.retry;

/**
 * Thrown when transient retries for a workflow stage reach maximum allowed attempts.
 */
public class RetriesExhaustedException extends RuntimeException {

    private final String stage;
    private final int totalAttempts;

    public RetriesExhaustedException(String stage, int totalAttempts, Throwable lastCause) {
        super("Retries exhausted for stage " + stage + " after " + totalAttempts + " attempts: " +
                (lastCause != null ? lastCause.getMessage() : "unknown transient failure"), lastCause);
        this.stage = stage;
        this.totalAttempts = totalAttempts;
    }

    public String getStage() {
        return stage;
    }

    public int getTotalAttempts() {
        return totalAttempts;
    }
}
