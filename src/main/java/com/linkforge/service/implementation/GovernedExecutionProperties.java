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
    private String fixedBuildCommand = "./mvnw --batch-mode clean verify";
    private int maxRepairAttempts = 2;
    private boolean containerIsolationAvailable = false;
    private String containerImage = "maven:3.9.9-eclipse-temurin-21@sha256:4f3c7c7423e2dc92b34208a0d24c08e56d7eb596238b6d0e82eb0aaec6655519";
    private String dependencyCachePath = null;

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

    public int getMaxRepairAttempts() {
        return maxRepairAttempts;
    }

    public void setMaxRepairAttempts(int maxRepairAttempts) {
        this.maxRepairAttempts = maxRepairAttempts;
    }

    public boolean isContainerIsolationAvailable() {
        // Must never treat flag alone as proof: requires both explicit configuration and functional runtime
        return this.containerIsolationAvailable && probeContainerRuntime();
    }

    public void setContainerIsolationAvailable(boolean containerIsolationAvailable) {
        this.containerIsolationAvailable = containerIsolationAvailable;
    }

    public String getContainerImage() {
        return containerImage;
    }

    public void setContainerImage(String containerImage) {
        this.containerImage = containerImage;
    }

    public String getDependencyCachePath() {
        return dependencyCachePath;
    }

    public void setDependencyCachePath(String dependencyCachePath) {
        this.dependencyCachePath = dependencyCachePath;
    }

    public static boolean probeContainerRuntime() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
