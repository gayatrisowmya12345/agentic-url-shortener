package com.linkforge.service.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Environment and property-backed configuration for bounded workflow retries and exponential backoff.
 */
@Component
@ConfigurationProperties(prefix = "linkforge.workflow.retry")
public class WorkflowRetryProperties {

    private boolean enabled = true;
    private int maxAttempts = 3;
    private long initialBackoffMs = 500;
    private long maxBackoffMs = 5000;
    private double backoffMultiplier = 2.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = Math.max(0, maxBackoffMs);
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = Math.max(1.0, backoffMultiplier);
    }

    /**
     * Calculates bounded exponential backoff delay for the given attempt index (1-based).
     * attempt = 1 -> initialBackoffMs
     * attempt = 2 -> initialBackoffMs * multiplier
     * attempt = n -> min(maxBackoffMs, initialBackoffMs * multiplier^(n-1))
     */
    public long calculateBackoffMs(int attempt) {
        if (attempt <= 1) {
            return Math.min(initialBackoffMs, maxBackoffMs);
        }
        double calculated = initialBackoffMs * Math.pow(backoffMultiplier, attempt - 1);
        long delay = Math.round(calculated);
        return Math.min(delay, maxBackoffMs);
    }
}
