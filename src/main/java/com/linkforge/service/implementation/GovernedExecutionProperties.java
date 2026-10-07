package com.linkforge.service.implementation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Safety boundaries, size limits, and command execution policies for governed implementation.
 */
@Component
@ConfigurationProperties(prefix = "linkforge.execution")
public class GovernedExecutionProperties {

    private boolean enabled = true;
    private Set<String> allowedExtensions = Set.of(".java", ".xml", ".properties", ".json", ".md");
    private int maxOperations = 10;
    private long maxFileSizeBytes = 500_000L;       // 500 KB per file
    private long maxTotalChangeBytes = 2_000_000L;  // 2 MB total per proposal
    private int buildTimeoutSeconds = 60;           // 60 seconds bounded execution
    private int maxCapturedOutputChars = 10_000;
    private String fixedBuildCommand = "./mvnw --batch-mode test -Dtest=CustomAliasValidationTest";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(Set<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public int getMaxOperations() {
        return maxOperations;
    }

    public void setMaxOperations(int maxOperations) {
        this.maxOperations = maxOperations;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public long getMaxTotalChangeBytes() {
        return maxTotalChangeBytes;
    }

    public void setMaxTotalChangeBytes(long maxTotalChangeBytes) {
        this.maxTotalChangeBytes = maxTotalChangeBytes;
    }

    public int getBuildTimeoutSeconds() {
        return buildTimeoutSeconds;
    }

    public void setBuildTimeoutSeconds(int buildTimeoutSeconds) {
        this.buildTimeoutSeconds = buildTimeoutSeconds;
    }

    public int getMaxCapturedOutputChars() {
        return maxCapturedOutputChars;
    }

    public void setMaxCapturedOutputChars(int maxCapturedOutputChars) {
        this.maxCapturedOutputChars = maxCapturedOutputChars;
    }

    public String getFixedBuildCommand() {
        return fixedBuildCommand;
    }

    public void setFixedBuildCommand(String fixedBuildCommand) {
        this.fixedBuildCommand = fixedBuildCommand;
    }
}
