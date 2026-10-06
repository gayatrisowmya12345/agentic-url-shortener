package com.linkforge.service.coordination;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "linkforge.coordination")
public class SpecialistCoordinationProperties {

    /**
     * Maximum number of specialist tasks running concurrently.
     */
    private int maxConcurrency = 2;

    /**
     * Timeout in seconds for an individual specialist task execution.
     */
    private int taskTimeoutSeconds = 10;

    /**
     * Maximum allowed agent invocations per workflow run.
     */
    private int maxInvocations = 20;

    /**
     * Maximum character count for specialist output summaries.
     */
    private int maxOutputChars = 4000;

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getTaskTimeoutSeconds() {
        return taskTimeoutSeconds;
    }

    public void setTaskTimeoutSeconds(int taskTimeoutSeconds) {
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    public int getMaxInvocations() {
        return maxInvocations;
    }

    public void setMaxInvocations(int maxInvocations) {
        this.maxInvocations = maxInvocations;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }
}
