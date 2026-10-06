package com.linkforge.ai.provider;

/**
 * Exception thrown when an LLM provider fails, times out, or is unavailable.
 */
public class LlmProviderException extends RuntimeException {

    public LlmProviderException(String message) {
        super(message);
    }

    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
