package com.linkforge.ai.provider;

import com.linkforge.ai.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI Ollama implementation of the LlmModelProvider interface.
 * Configures JSON format constraint and token limits to ensure complete JSON responses.
 */
@Component
public class OllamaModelProvider implements LlmModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelProvider.class);

    private final LlmProperties properties;
    private final ObjectProvider<ChatModel> chatModelProvider;

    public OllamaModelProvider(LlmProperties properties, ObjectProvider<ChatModel> chatModelProvider) {
        this.properties = properties;
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled() && "ollama".equalsIgnoreCase(properties.getProvider());
    }

    @Override
    public String getProviderId() {
        return "ollama";
    }

    @Override
    public String getModelName() {
        return properties.getModel();
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        if (!isEnabled()) {
            throw new LlmProviderException("Ollama provider is disabled in configuration.");
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new LlmProviderException("Spring AI ChatModel is not available in the application context.");
        }

        try {
            log.info("Dispatching prompt to Ollama model '{}' at '{}'", properties.getModel(), properties.getBaseUrl());
            OllamaChatOptions options = OllamaChatOptions.builder()
                    .model(properties.getModel())
                    .format("json")
                    .numPredict(2048)
                    .temperature(0.2)
                    .build();

            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ), options);

            var response = chatModel.call(prompt);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new LlmProviderException("Empty response received from Ollama model.");
            }

            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new LlmProviderException("Blank response content received from Ollama model.");
            }

            return text;
        } catch (Exception e) {
            log.warn("Ollama invocation failed: {}", e.getMessage());
            throw new LlmProviderException("Ollama communication failure: " + e.getMessage(), e);
        }
    }
}
