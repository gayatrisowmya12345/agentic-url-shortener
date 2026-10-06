package com.linkforge.ai.provider;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controllable fake LLM provider for testing model-backed agent workflows,
 * failure fallbacks, and deterministic modes.
 */
public class FakeLlmModelProvider implements LlmModelProvider {

    private boolean enabled = true;
    private String responsePayload;
    private RuntimeException exceptionToThrow;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicBoolean wasCalled = new AtomicBoolean(false);
    private String lastSystemPrompt;
    private String lastUserPrompt;

    public FakeLlmModelProvider() {
    }

    public FakeLlmModelProvider(String responsePayload) {
        this.responsePayload = responsePayload;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setResponsePayload(String responsePayload) {
        this.responsePayload = responsePayload;
        this.exceptionToThrow = null;
    }

    public void setExceptionToThrow(RuntimeException exceptionToThrow) {
        this.exceptionToThrow = exceptionToThrow;
    }

    public int getCallCount() {
        return callCount.get();
    }

    public boolean wasCalled() {
        return wasCalled.get();
    }

    public String getLastSystemPrompt() {
        return lastSystemPrompt;
    }

    public String getLastUserPrompt() {
        return lastUserPrompt;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getProviderId() {
        return "fake";
    }

    @Override
    public String getModelName() {
        return "fake-model-v1";
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        wasCalled.set(true);
        callCount.incrementAndGet();
        this.lastSystemPrompt = systemPrompt;
        this.lastUserPrompt = userPrompt;

        if (exceptionToThrow != null) {
            throw exceptionToThrow;
        }
        return responsePayload;
    }
}
