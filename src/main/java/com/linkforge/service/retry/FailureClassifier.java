package com.linkforge.service.retry;

import com.linkforge.domain.workflow.scenario.exception.InspectionException;
import com.linkforge.domain.workflow.scenario.exception.InspectionSecurityException;
import com.linkforge.domain.workflow.specialist.exception.TaskGraphException;
import com.linkforge.service.security.InvalidPlanHashException;
import com.linkforge.service.security.WorkflowAuthorizationException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Classifies exceptions into TRANSIENT (retryable) or NON_RETRYABLE failures.
 * Transient errors include network connectivity failures, socket/call timeouts, and temporary rate limits.
 * Non-retryable errors include invalid input, authorization failures, rejected plans, inspection security, and graph cycles.
 */
public final class FailureClassifier {

    private FailureClassifier() {}

    public static FailureClassification classify(Throwable throwable) {
        if (throwable == null) {
            return FailureClassification.NON_RETRYABLE;
        }

        // First check the entire cause chain for explicit non-retryable errors
        if (hasNonRetryableCause(throwable)) {
            return FailureClassification.NON_RETRYABLE;
        }

        // Then check if the exception or any cause in the chain is transient
        if (hasTransientCause(throwable)) {
            return FailureClassification.TRANSIENT;
        }

        return FailureClassification.NON_RETRYABLE;
    }

    public static boolean isTransient(Throwable throwable) {
        return classify(throwable) == FailureClassification.TRANSIENT;
    }

    private static boolean hasNonRetryableCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof InspectionException
                    || current instanceof InspectionSecurityException
                    || current instanceof WorkflowAuthorizationException
                    || current instanceof InvalidPlanHashException
                    || current instanceof TaskGraphException
                    || current instanceof IllegalArgumentException
                    || current instanceof IllegalStateException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static boolean hasTransientCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof TransientWorkflowException
                    || current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("timed out")
                        || lower.contains("timeout")
                        || lower.contains("connection reset")
                        || lower.contains("connection refused")
                        || lower.contains("connect timed out")
                        || lower.contains("rate limit")
                        || lower.contains("429")
                        || lower.contains("503")
                        || lower.contains("service unavailable")
                        || lower.contains("temporarily unavailable")
                        || lower.contains("transient")) {
                    return true;
                }
            }

            current = current.getCause();
            depth++;
        }
        return false;
    }
}
