package com.linkforge.agent.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.ai.provider.LlmModelProvider;
import com.linkforge.ai.provider.LlmProviderException;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.specialist.SpecialistTaskInput;
import com.linkforge.domain.workflow.specialist.SpecialistTaskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecialistModelBackedAndFallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Model-backed specialist executes successfully when provider returns valid structured JSON")
    void successfulModelBackedExecution() {
        LlmModelProvider provider = new LlmModelProvider() {
            @Override public boolean isEnabled() { return true; }
            @Override public String getProviderId() { return "ollama"; }
            @Override public String getModelName() { return "llama3.2"; }
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                return """
                        {
                          "summary": "Model-derived REST API design for URL shortener endpoints",
                          "recommendations": [
                            "Use POST /api/v1/links for link generation returning HTTP 201",
                            "Implement GET /{token} returning HTTP 302 Found"
                          ],
                          "testIdeas": [
                            "Assert HTTP 302 with Location header on redirect",
                            "Assert HTTP 400 when destination URL is invalid"
                          ],
                          "addressedCriteria": ["AC-1", "AC-3"]
                        }
                        """;
            }
        };

        ApiBehaviorSpecialistAgent agent = new ApiBehaviorSpecialistAgent(provider, objectMapper);
        SpecialistTaskInput input = new SpecialistTaskInput(
                "TASK-3", "REST Endpoints", "API design", List.of(), Map.of(),
                "Requirement", List.of("AC-1", "AC-3"), Scenario.GREENFIELD, Map.of()
        );

        SpecialistTaskResult result = agent.execute(input);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.summary()).contains("Model-derived REST API design");
        assertThat(result.recommendations()).hasSize(2);
        assertThat(result.testIdeas()).hasSize(2);
        assertThat(result.addressedCriteria()).containsExactly("AC-1", "AC-3");
        assertThat(result.metadata().get("provider")).isEqualTo("ollama");
        assertThat(result.metadata().get("model")).isEqualTo("llama3.2");
        assertThat(result.metadata().get("type")).isEqualTo("MODEL_BACKED_SPECIALIST");
    }

    @Test
    @DisplayName("Specialist unwraps Markdown code fences returned by model")
    void handlesMarkdownCodeFencedJson() {
        LlmModelProvider provider = new LlmModelProvider() {
            @Override public boolean isEnabled() { return true; }
            @Override public String getProviderId() { return "ollama"; }
            @Override public String getModelName() { return "llama3.2"; }
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                return """
                        ```json
                        {
                          "summary": "Clean markdown-wrapped output",
                          "recommendations": ["Recommendation A", "Recommendation B"],
                          "testIdeas": ["Test A"],
                          "addressedCriteria": ["AC-1"]
                        }
                        ```
                        """;
            }
        };

        DataPersistenceSpecialistAgent agent = new DataPersistenceSpecialistAgent(provider, objectMapper);
        SpecialistTaskInput input = new SpecialistTaskInput(
                "TASK-1", "Persistence", "Schema design", List.of(), Map.of(),
                "Requirement", List.of("AC-1"), Scenario.GREENFIELD, Map.of()
        );

        SpecialistTaskResult result = agent.execute(input);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.summary()).isEqualTo("Clean markdown-wrapped output");
    }

    @Test
    @DisplayName("Model provider timeout or connection failure triggers safe deterministic fallback")
    void providerFailureTriggersDeterministicFallback() {
        LlmModelProvider failingProvider = new LlmModelProvider() {
            @Override public boolean isEnabled() { return true; }
            @Override public String getProviderId() { return "ollama"; }
            @Override public String getModelName() { return "llama3.2"; }
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                throw new LlmProviderException("Connection refused to Ollama host at http://localhost:11434");
            }
        };

        SecurityValidationSpecialistAgent agent = new SecurityValidationSpecialistAgent(failingProvider, objectMapper);
        SpecialistTaskInput input = new SpecialistTaskInput(
                "TASK-2", "Protocol Validation", "Sanitization", List.of(), Map.of(),
                "Requirement", List.of("AC-2"), Scenario.GREENFIELD, Map.of()
        );

        SpecialistTaskResult result = agent.execute(input);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("Connection refused");
        assertThat(result.recommendations()).isNotEmpty();
        assertThat(result.metadata().get("type")).isEqualTo("DETERMINISTIC_SPECIALIST_FALLBACK");
    }

    @Test
    @DisplayName("Malformed or truncated JSON triggers deterministic fallback")
    void truncatedJsonTriggersFallback() {
        LlmModelProvider truncatedProvider = new LlmModelProvider() {
            @Override public boolean isEnabled() { return true; }
            @Override public String getProviderId() { return "ollama"; }
            @Override public String getModelName() { return "llama3.2"; }
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                return "{\"summary\": \"Truncated json missing closing braces";
            }
        };

        TestingQualitySpecialistAgent agent = new TestingQualitySpecialistAgent(truncatedProvider, objectMapper);
        SpecialistTaskInput input = new SpecialistTaskInput(
                "TASK-5", "Quality", "Tests", List.of(), Map.of(),
                "Requirement", List.of("AC-1"), Scenario.GREENFIELD, Map.of()
        );

        SpecialistTaskResult result = agent.execute(input);

        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("Failed to parse JSON");
        assertThat(result.recommendations()).isNotEmpty();
    }

    @Test
    @DisplayName("Disabled model provider uses deterministic specialist directly without fallback flag")
    void disabledProviderUsesDeterministicRulesDirectly() {
        LlmModelProvider disabledProvider = new LlmModelProvider() {
            @Override public boolean isEnabled() { return false; }
            @Override public String getProviderId() { return "ollama"; }
            @Override public String getModelName() { return "llama3.2"; }
            @Override
            public String generate(String systemPrompt, String userPrompt) {
                throw new UnsupportedOperationException("Should not be called");
            }
        };

        ApiBehaviorSpecialistAgent agent = new ApiBehaviorSpecialistAgent(disabledProvider, objectMapper);
        SpecialistTaskInput input = new SpecialistTaskInput(
                "TASK-3", "Token Gen", "Base62 generator", List.of(), Map.of(),
                "Requirement", List.of("AC-1"), Scenario.GREENFIELD, Map.of()
        );

        SpecialistTaskResult result = agent.execute(input);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.metadata().get("type")).isEqualTo("DETERMINISTIC_SPECIALIST");
    }
}
