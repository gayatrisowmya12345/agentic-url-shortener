package com.linkforge.ai.provider;

/**
 * Provider abstraction decoupling workflow agents from specific LLM implementations
 * such as Spring AI Ollama, cloud providers, or test fakes.
 */
public interface LlmModelProvider {

    /**
     * Whether this provider is enabled and active in configuration.
     */
    boolean isEnabled();

    /**
     * Unique identifier for the provider (e.g., "ollama", "fake").
     */
    String getProviderId();

    /**
     * Configured model name (e.g., "llama3.2").
     */
    String getModelName();

    /**
     * Generates a response from the model given a system prompt and a user requirement prompt.
     *
     * @param systemPrompt system instructions and formatting rules
     * @param userPrompt user requirement text
     * @return raw response text from the model
     * @throws LlmProviderException if the provider is unavailable, times out, or fails
     */
    String generate(String systemPrompt, String userPrompt);
}
