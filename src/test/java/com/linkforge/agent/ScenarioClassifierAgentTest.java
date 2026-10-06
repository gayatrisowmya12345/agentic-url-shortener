package com.linkforge.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.ai.provider.FakeLlmModelProvider;
import com.linkforge.ai.provider.LlmProviderException;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.scenario.ScenarioClassificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioClassifierAgentTest {

    private FakeLlmModelProvider fakeProvider;
    private ObjectMapper objectMapper;
    private ScenarioClassifierAgent agent;

    @BeforeEach
    void setUp() {
        fakeProvider = new FakeLlmModelProvider();
        objectMapper = new ObjectMapper();
        agent = new ScenarioClassifierAgent(fakeProvider, objectMapper);
    }

    @Test
    @DisplayName("Deterministic classification recognizes GREENFIELD request")
    void deterministicGreenfield() {
        fakeProvider.setEnabled(false);

        ScenarioClassificationResult result = agent.classify("Create a new distributed URL shortening service");

        assertThat(result.scenario()).isEqualTo(Scenario.GREENFIELD);
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.evidenceSignals()).contains("new_system_declaration");
    }

    @Test
    @DisplayName("Deterministic classification recognizes BROWNFIELD request referencing existing repository")
    void deterministicBrownfield() {
        fakeProvider.setEnabled(false);

        ScenarioClassificationResult result = agent.classify("Refactor the existing repository to migrate database queries");

        assertThat(result.scenario()).isEqualTo(Scenario.BROWNFIELD);
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.evidenceSignals()).anyMatch(s -> s.contains("existing") || s.contains("refactor") || s.contains("repository"));
    }

    @Test
    @DisplayName("Deterministic classification recognizes BROWNFIELD when repository path is supplied with modification verbs")
    void deterministicBrownfieldWithRepoPath() {
        fakeProvider.setEnabled(false);

        ScenarioClassificationResult result = agent.classify("Update link eviction logic and extend caching", "/repos/agentic-url-shortener");

        assertThat(result.scenario()).isEqualTo(Scenario.BROWNFIELD);
        assertThat(result.evidenceSignals()).contains("repository_path_supplied");
    }

    @Test
    @DisplayName("Deterministic classification detects AMBIGUOUS request lacking verifiable bounds")
    void deterministicAmbiguous() {
        fakeProvider.setEnabled(false);

        ScenarioClassificationResult result = agent.classify("make links faster and safer");

        assertThat(result.scenario()).isEqualTo(Scenario.AMBIGUOUS);
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.clarificationQuestions()).isNotEmpty();
        assertThat(result.clarificationQuestions().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Deterministic classification treats overly short vague prompt as AMBIGUOUS")
    void deterministicShortPromptAmbiguous() {
        fakeProvider.setEnabled(false);

        ScenarioClassificationResult result = agent.classify("improve links");

        assertThat(result.scenario()).isEqualTo(Scenario.AMBIGUOUS);
        assertThat(result.clarificationQuestions()).isNotEmpty();
    }

    @Test
    @DisplayName("Model-backed classification succeeds for GREENFIELD requirement")
    void modelBackedGreenfield() {
        fakeProvider.setEnabled(true);
        String payload = """
                {
                  "scenario": "GREENFIELD",
                  "rationale": "Requirement describes building a new microservice architecture from scratch.",
                  "confidence": 0.98,
                  "evidenceSignals": ["brand new service", "greenfield"],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setResponsePayload(payload);

        ScenarioClassificationResult result = agent.classify("Build a new distributed link service");

        assertThat(fakeProvider.wasCalled()).isTrue();
        assertThat(result.scenario()).isEqualTo(Scenario.GREENFIELD);
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.confidence()).isEqualTo(0.98);
        assertThat(result.metadata().get("type")).isEqualTo(ScenarioClassifierAgent.AGENT_TYPE_MODEL_BACKED);
    }

    @Test
    @DisplayName("Model-backed classification succeeds for BROWNFIELD requirement")
    void modelBackedBrownfield() {
        fakeProvider.setEnabled(true);
        String payload = """
                {
                  "scenario": "BROWNFIELD",
                  "rationale": "Requirement explicitly targets refactoring our current codebase.",
                  "confidence": 0.95,
                  "evidenceSignals": ["existing codebase", "refactoring"],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setResponsePayload(payload);

        ScenarioClassificationResult result = agent.classify("Refactor existing codebase", "/path/to/repo");

        assertThat(result.scenario()).isEqualTo(Scenario.BROWNFIELD);
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.metadata().get("type")).isEqualTo(ScenarioClassifierAgent.AGENT_TYPE_MODEL_BACKED);
    }

    @Test
    @DisplayName("Model-backed classification succeeds for AMBIGUOUS requirement with questions")
    void modelBackedAmbiguous() {
        fakeProvider.setEnabled(true);
        String payload = """
                {
                  "scenario": "AMBIGUOUS",
                  "rationale": "Requirement provides no measurable targets or system boundaries.",
                  "confidence": 0.88,
                  "evidenceSignals": ["qualitative only"],
                  "clarificationQuestions": [
                    "Is this a greenfield system or brownfield modification?",
                    "What target latency metric is required?"
                  ]
                }
                """;
        fakeProvider.setResponsePayload(payload);

        ScenarioClassificationResult result = agent.classify("make things better");

        assertThat(result.scenario()).isEqualTo(Scenario.AMBIGUOUS);
        assertThat(result.clarificationQuestions()).hasSize(2);
    }

    @Test
    @DisplayName("Model provider failure triggers deterministic fallback")
    void modelFailureTriggersFallback() {
        fakeProvider.setEnabled(true);
        fakeProvider.setExceptionToThrow(new LlmProviderException("Ollama host unavailable"));

        ScenarioClassificationResult result = agent.classify("Build a new URL shortener");

        assertThat(result.scenario()).isEqualTo(Scenario.GREENFIELD);
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("Ollama host unavailable");
        assertThat(result.metadata().get("type")).isEqualTo(ScenarioClassifierAgent.AGENT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("Truncated / malformed JSON triggers deterministic fallback")
    void truncatedModelOutputTriggersFallback() {
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload("{\"scenario\": \"GREENFIELD\", \"rationale\": \"incomplete string");

        ScenarioClassificationResult result = agent.classify("Refactor the existing database repository");

        assertThat(result.scenario()).isEqualTo(Scenario.BROWNFIELD);
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.metadata().get("type")).isEqualTo(ScenarioClassifierAgent.AGENT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("Unknown scenario string from model triggers deterministic fallback")
    void unknownScenarioTriggersFallback() {
        fakeProvider.setEnabled(true);
        String payload = """
                {
                  "scenario": "INVALID_SCENARIO_NAME",
                  "rationale": "Unsupported enum value",
                  "confidence": 0.5,
                  "evidenceSignals": [],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setResponsePayload(payload);

        ScenarioClassificationResult result = agent.classify("Build a new URL shortener");

        assertThat(result.scenario()).isEqualTo(Scenario.GREENFIELD);
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("unknown scenario");
    }
}
