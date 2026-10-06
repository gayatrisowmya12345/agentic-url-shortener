package com.linkforge.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "linkforge.ai")
public class LlmProperties {

    /**
     * Whether LLM model-backed agent calls are enabled.
     * When false (default), the workbench operates in pure deterministic mode.
     */
    private boolean enabled = false;

    /**
     * Active model provider identifier (e.g., "ollama", "fake").
     */
    private String provider = "ollama";

    /**
     * Model name (e.g., "llama3.2", "mistral", "qwen2.5").
     */
    private String model = "llama3.2";

    /**
     * Base URL for the model service (e.g., "http://localhost:11434").
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * Timeout in seconds for model invocations.
     */
    private int timeoutSeconds = 15;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
