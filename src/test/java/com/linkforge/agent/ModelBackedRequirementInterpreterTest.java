package com.linkforge.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.ai.provider.FakeLlmModelProvider;
import com.linkforge.ai.provider.LlmProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelBackedRequirementInterpreterTest {

    private FakeLlmModelProvider fakeProvider;
    private ObjectMapper objectMapper;
    private RequirementInterpreterAgent agent;

    @BeforeEach
    void setUp() {
        fakeProvider = new FakeLlmModelProvider();
        objectMapper = new ObjectMapper();
        agent = new RequirementInterpreterAgent(fakeProvider, objectMapper);
    }

    @Test
    @DisplayName("Model provider is called when enabled")
    void providerIsCalledWhenEnabled() {
        String validResponse = """
                {
                  "summary": "Valid model analysis of URL shortener",
                  "acceptanceCriteria": [
                    "AC-MODEL-1: Given a long URL, when shortened, return a 7-character code"
                  ],
                  "assumptions": [
                    "HTTP/HTTPS schemes only"
                  ],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(validResponse);

        RequirementInterpretationResult result = agent.interpret("Create a URL shortener service");

        assertThat(fakeProvider.wasCalled()).isTrue();
        assertThat(fakeProvider.getCallCount()).isEqualTo(1);
        assertThat(fakeProvider.getLastUserPrompt()).isEqualTo("Create a URL shortener service");
        assertThat(fakeProvider.getLastSystemPrompt()).contains("LinkForge");
        assertThat(result.fallbackOccurred()).isFalse();
    }

    @Test
    @DisplayName("Valid model output affects the workflow result")
    void validModelOutputAffectsResult() {
        String validResponse = """
                {
                  "summary": "Custom model-backed URL shortening specification",
                  "acceptanceCriteria": [
                    "AC-CUSTOM-1: Given an input URL, model generates custom base62 slug",
                    "AC-CUSTOM-2: Given active slug, redirect with HTTP 302"
                  ],
                  "assumptions": [
                    "Cache short codes in Redis L1 cache",
                    "Rate limit link creation to 10 requests per second"
                  ],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(validResponse);

        RequirementInterpretationResult result = agent.interpret("Create an enterprise URL shortener");

        assertThat(result.clear()).isTrue();
        assertThat(result.decisionSummary()).isEqualTo("LLM_ANALYSIS_ACCEPTED");
        assertThat(result.rationale()).isEqualTo("Custom model-backed URL shortening specification");
        assertThat(result.acceptanceCriteria()).containsExactly(
                "AC-CUSTOM-1: Given an input URL, model generates custom base62 slug",
                "AC-CUSTOM-2: Given active slug, redirect with HTTP 302"
        );
        assertThat(result.assumptions()).containsExactly(
                "Cache short codes in Redis L1 cache",
                "Rate limit link creation to 10 requests per second"
        );
        assertThat(result.unansweredQuestions()).isEmpty();
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_MODEL_BACKED);
        assertThat(result.metadata()).containsEntry("provider", "fake");
        assertThat(result.metadata()).containsEntry("model", "fake-model-v1");
        assertThat(result.fallbackOccurred()).isFalse();
    }

    @Test
    @DisplayName("Valid model output wrapped in Markdown code blocks parses successfully")
    void validMarkdownWrappedModelOutputParsesSuccessfully() {
        String markdownResponse = """
                ```json
                {
                  "summary": "Markdown-wrapped specification",
                  "acceptanceCriteria": [
                    "AC-1: Given valid URL, return short code"
                  ],
                  "assumptions": [
                    "Port 8080 used"
                  ],
                  "clarificationQuestions": []
                }
                ```
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(markdownResponse);

        RequirementInterpretationResult result = agent.interpret("Create a URL shortener service");

        assertThat(result.clear()).isTrue();
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.acceptanceCriteria()).containsExactly("AC-1: Given valid URL, return short code");
        assertThat(result.assumptions()).containsExactly("Port 8080 used");
    }

    @Test
    @DisplayName("Valid model output with conversational text surroundings parses successfully")
    void validModelOutputWithConversationalSurroundingsParsesSuccessfully() {
        String conversationalResponse = """
                Here is the JSON specification for your requirement:
                ```json
                {
                  "summary": "Clean JSON embedded in conversational prose",
                  "acceptanceCriteria": [
                    "AC-1: Given URL, generate token"
                  ],
                  "assumptions": [
                    "Deterministic fallback available"
                  ],
                  "clarificationQuestions": []
                }
                ```
                Hope this meets your requirements! Let me know if you need modifications.
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(conversationalResponse);

        RequirementInterpretationResult result = agent.interpret("Create a URL shortener service");

        assertThat(result.clear()).isTrue();
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.acceptanceCriteria()).containsExactly("AC-1: Given URL, generate token");
        assertThat(result.assumptions()).containsExactly("Deterministic fallback available");
    }

    @Test
    @DisplayName("Valid model clarification questions pause workflow")
    void validModelClarificationQuestions() {
        String ambiguousModelResponse = """
                {
                  "summary": "Requirement lacks performance metrics and encryption details",
                  "acceptanceCriteria": [],
                  "assumptions": [],
                  "clarificationQuestions": [
                    "What target latency is acceptable for URL resolution?",
                    "Are short links public or authenticated?"
                  ]
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(ambiguousModelResponse);

        RequirementInterpretationResult result = agent.interpret("make links faster and safer");

        assertThat(result.clear()).isFalse();
        assertThat(result.decisionSummary()).isEqualTo("LLM_CLARIFICATION_REQUIRED");
        assertThat(result.unansweredQuestions()).hasSize(2);
        assertThat(result.acceptanceCriteria()).isEmpty();
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_MODEL_BACKED);
        assertThat(result.fallbackOccurred()).isFalse();
    }

    @Test
    @DisplayName("Unavailable provider falls back safely to deterministic behavior and records fallback")
    void unavailableProviderFallsBackSafely() {
        fakeProvider.setEnabled(true);
        fakeProvider.setExceptionToThrow(new LlmProviderException("Connection refused to Ollama daemon on localhost:11434"));

        RequirementInterpretationResult result = agent.interpret("Build a URL shortener that shortens links and redirects users");

        // Verifies fallback to deterministic output
        assertThat(result.clear()).isTrue();
        assertThat(result.acceptanceCriteria()).isNotEmpty();
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("Connection refused to Ollama daemon");
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_FALLBACK);
        assertThat(result.metadata()).containsEntry("attemptedProvider", "fake");
    }

    @Test
    @DisplayName("Truncated model output missing closing brace falls back safely")
    void truncatedModelOutputMissingClosingBraceFallsBack() {
        // Truncated response before completing root JSON object
        String truncatedResponse = """
                {
                  "summary": "Truncated analysis",
                  "acceptanceCriteria": [
                    "AC-1: Given a long URL, when shortened, then return token"
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(truncatedResponse);

        RequirementInterpretationResult result = agent.interpret("Build a URL shortener service");

        assertThat(result.clear()).isTrue();
        assertThat(result.acceptanceCriteria()).isNotEmpty();
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("truncated or incomplete");
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("Truncated output ending abruptly mid-token (unexpected end-of-input) falls back safely")
    void truncatedOutputEndingAbruptlyMidTokenFallsBack() {
        // Output cut off abruptly inside a property value
        String truncatedMidToken = "{\"summary\": \"Analysis in prog";
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(truncatedMidToken);

        RequirementInterpretationResult result = agent.interpret("Build a URL shortener service");

        assertThat(result.clear()).isTrue();
        assertThat(result.acceptanceCriteria()).isNotEmpty();
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("Malformed non-JSON model output falls back safely to deterministic behavior")
    void malformedOutputFallsBackSafely() {
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload("This is definitely not valid JSON from the LLM.");

        RequirementInterpretationResult result = agent.interpret("Build a URL shortener service");

        assertThat(result.clear()).isTrue();
        assertThat(result.acceptanceCriteria()).isNotEmpty();
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).isNotBlank();
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("Invalid model output failing application validation falls back safely")
    void invalidModelOutputMissingCriteriaAndQuestionsFallsBack() {
        // Output has empty criteria AND empty questions — invalid!
        String invalidEmptyOutput = """
                {
                  "summary": "Invalid analysis with no criteria and no questions",
                  "acceptanceCriteria": [],
                  "assumptions": [],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(invalidEmptyOutput);

        RequirementInterpretationResult result = agent.interpret("Build a URL shortener service");

        assertThat(result.clear()).isTrue();
        assertThat(result.acceptanceCriteria()).isNotEmpty();
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("must provide either acceptanceCriteria or clarificationQuestions");
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("Deterministic mode works without Ollama when provider is disabled")
    void deterministicModeWorksWhenDisabled() {
        fakeProvider.setEnabled(false);

        // 1. Clear path
        RequirementInterpretationResult clearResult = agent.interpret("Build a URL shortener that shortens links and redirects users");
        assertThat(fakeProvider.wasCalled()).isFalse();
        assertThat(clearResult.clear()).isTrue();
        assertThat(clearResult.acceptanceCriteria()).isNotEmpty();
        assertThat(clearResult.assumptions()).isNotEmpty();
        assertThat(clearResult.fallbackOccurred()).isFalse();
        assertThat(clearResult.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_DETERMINISTIC);

        // 2. Ambiguous path
        RequirementInterpretationResult ambiguousResult = agent.interpret("make links faster and safer");
        assertThat(fakeProvider.wasCalled()).isFalse();
        assertThat(ambiguousResult.clear()).isFalse();
        assertThat(ambiguousResult.unansweredQuestions()).isNotEmpty();
        assertThat(ambiguousResult.fallbackOccurred()).isFalse();
        assertThat(ambiguousResult.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_DETERMINISTIC);
    }

    @Test
    @DisplayName("Model preserves all requested capabilities: link creation, custom aliases, redirect behavior, and internal click analytics")
    void modelPreservesAllRequestedCapabilitiesForShortUrlWithAliasesAndAnalytics() {
        String requirement = "Create a short URL service with custom aliases and click analytics";
        String modelResponse = """
                {
                  "summary": "Specification for short URL service with custom aliases, redirection, and internal click analytics",
                  "acceptanceCriteria": [
                    "AC-1: Given a valid target destination URL, when creating a short link, then the system generates a unique short URL token.",
                    "AC-2: Given a requested custom alias, when available, then the system binds the custom alias to the target URL and rejects duplicates with HTTP 409.",
                    "AC-3: Given an active short URL or custom alias, when accessed via GET, then the system redirects with HTTP 302 to the original destination URL.",
                    "AC-4: Given an existing short URL, when a redirect is performed, then the system increments the internal click count and records the access timestamp."
                  ],
                  "assumptions": [
                    "Custom aliases must be between 3 and 30 alphanumeric characters",
                    "Click analytics are stored internally in the application database without third-party services"
                  ],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(modelResponse);

        RequirementInterpretationResult result = agent.interpret(requirement);

        assertThat(result.clear()).isTrue();
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_MODEL_BACKED);

        // Verify system prompt instructs model on domain rules and forbidden third-party invention
        String systemPrompt = fakeProvider.getLastSystemPrompt();
        assertThat(systemPrompt).contains("Link creation");
        assertThat(systemPrompt).contains("Custom aliases");
        assertThat(systemPrompt).contains("Redirect behavior");
        assertThat(systemPrompt).contains("Click analytics");
        assertThat(systemPrompt).contains("Do NOT invent third-party analytics services");

        // Verify all 4 capabilities are preserved in acceptance criteria
        List<String> acs = result.acceptanceCriteria();
        assertThat(acs).hasSize(4);
        assertThat(acs).anyMatch(ac -> ac.toLowerCase().contains("short url") || ac.toLowerCase().contains("short link") || ac.toLowerCase().contains("generates"));
        assertThat(acs).anyMatch(ac -> ac.toLowerCase().contains("custom alias"));
        assertThat(acs).anyMatch(ac -> ac.toLowerCase().contains("redirect") && ac.contains("302"));
        assertThat(acs).anyMatch(ac -> ac.toLowerCase().contains("click") && (ac.toLowerCase().contains("analytics") || ac.toLowerCase().contains("count")));

        // Verify model output does NOT invent a third-party analytics service
        for (String ac : acs) {
            assertThat(ac.toLowerCase()).doesNotContain("google analytics", "mixpanel", "segment", "amplitude");
        }
        for (String assumption : result.assumptions()) {
            assertThat(assumption.toLowerCase()).doesNotContain("google analytics", "mixpanel", "segment", "amplitude");
        }
    }

    @Test
    @DisplayName("Model output preserves third-party service only when requirement explicitly asks for one")
    void modelPreservesThirdPartyAnalyticsServiceOnlyWhenExplicitlyRequested() {
        String requirement = "Create a short URL service with Google Analytics integration";
        String modelResponse = """
                {
                  "summary": "URL shortener with explicit third-party Google Analytics integration",
                  "acceptanceCriteria": [
                    "AC-1: Given a destination URL, when requested, then generate a unique short link.",
                    "AC-2: Given an active short link, when resolved, then redirect with HTTP 302 to destination URL.",
                    "AC-3: Given a redirect event, when processed, then send hit event to Google Analytics Measurement Protocol."
                  ],
                  "assumptions": [
                    "Google Analytics Measurement ID is provided via configuration"
                  ],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(modelResponse);

        RequirementInterpretationResult result = agent.interpret(requirement);

        assertThat(result.clear()).isTrue();
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.acceptanceCriteria()).anyMatch(ac -> ac.contains("Google Analytics"));
        assertThat(result.assumptions()).anyMatch(a -> a.contains("Google Analytics"));
    }

    @Test
    @DisplayName("Valid model output containing extra JSON fields is parsed gracefully without failing")
    void validModelOutputWithExtraFieldsIgnoredGracefully() {
        String responseWithExtra = """
                {
                  "summary": "Model analysis with extra reasoning field",
                  "acceptanceCriteria": [
                    "AC-1: Given URL, generate token",
                    "AC-2: Given token, redirect to URL"
                  ],
                  "assumptions": [
                    "Internal storage only"
                  ],
                  "clarificationQuestions": [],
                  "reasoning": "Selected standard 302 redirect for SEO preservation",
                  "status": "SUCCESS"
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(responseWithExtra);

        RequirementInterpretationResult result = agent.interpret("Create a short URL service");

        assertThat(result.clear()).isTrue();
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.acceptanceCriteria()).hasSize(2);
        assertThat(result.assumptions()).hasSize(1);
    }

    @Test
    @DisplayName("Invalid model output with blank assumption falls back safely")
    void invalidModelOutputWithBlankAssumptionFallsBack() {
        String invalidAssumptionOutput = """
                {
                  "summary": "Analysis with blank assumption entry",
                  "acceptanceCriteria": [
                    "AC-1: Given valid URL, return short code"
                  ],
                  "assumptions": [
                    "   "
                  ],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(invalidAssumptionOutput);

        RequirementInterpretationResult result = agent.interpret("Build a URL shortener service");

        assertThat(result.clear()).isTrue();
        assertThat(result.acceptanceCriteria()).isNotEmpty();
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("assumption entries cannot be blank");
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("Actionable requirement blocked by empty criteria and non-essential clarification falls back safely")
    void actionableRequirementBlockedByEmptyCriteriaFallsBackSafely() {
        // Model erroneously returned empty criteria and questions about click update frequency for an actionable request
        String modelBlockedOnQuestions = """
                {
                  "summary": "Short URL service requirement analysis",
                  "acceptanceCriteria": [],
                  "assumptions": [],
                  "clarificationQuestions": [
                    "What click data should be recorded?",
                    "How often should click statistics be updated?"
                  ]
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(modelBlockedOnQuestions);

        RequirementInterpretationResult result = agent.interpret("Create a short URL service with custom aliases and click analytics");

        // Should fall back safely to deterministic rules so the workflow is not blocked
        assertThat(result.clear()).isTrue();
        assertThat(result.acceptanceCriteria()).isNotEmpty();
        assertThat(result.fallbackOccurred()).isTrue();
        assertThat(result.fallbackReason()).contains("actionable requirement yielded no acceptance criteria");
        assertThat(result.metadata()).containsEntry("type", RequirementInterpreterAgent.AGENT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("Model returning criteria alongside incidental questions does not block useful criteria")
    void modelReturningCriteriaAlongsideIncidentalQuestionsDoesNotBlock() {
        String responseWithIncidentalQuestion = """
                {
                  "summary": "Short URL service with custom aliases and click analytics",
                  "acceptanceCriteria": [
                    "AC-1: Given destination URL, system generates unique short link",
                    "AC-2: Given custom alias, system assigns alias to destination URL",
                    "AC-3: Given short link, system redirects with HTTP 302 to destination URL",
                    "AC-4: Given redirect event, system increments internal click counter"
                  ],
                  "assumptions": [
                    "Internal memory/database used for counters"
                  ],
                  "clarificationQuestions": [
                    "How often should analytics counters be aggregated?"
                  ]
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(responseWithIncidentalQuestion);

        RequirementInterpretationResult result = agent.interpret("Create a short URL service with custom aliases and click analytics");

        assertThat(result.clear()).isTrue();
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.decisionSummary()).isEqualTo("LLM_ANALYSIS_ACCEPTED");
        assertThat(result.acceptanceCriteria()).hasSize(4);
        // The incidental question is preserved in assumptions as a design consideration without stalling
        assertThat(result.assumptions()).anyMatch(a -> a.contains("How often should analytics counters be aggregated?"));
    }

    @Test
    @DisplayName("Unsupported per-user limit or quota is excluded from assumptions for actionable URL shortener requirement")
    void unsupportedPerUserLimitIsExcludedFromAssumptions() {
        String requirement = "Create a short URL service with custom aliases and click analytics";
        String modelResponseWithInventedQuota = """
                {
                  "summary": "Short URL service with custom aliases and click analytics",
                  "acceptanceCriteria": [
                    "AC-1: Given a valid destination URL, when creating short link, then system generates unique short URL token.",
                    "AC-2: Given custom alias, when requested, then system binds alias to destination URL.",
                    "AC-3: Given short URL token, when resolved, then system redirects with HTTP 302 to destination URL.",
                    "AC-4: Given redirect event, when performed, then system records internal click analytics."
                  ],
                  "assumptions": [
                    "The service uses a standard HTTP 302 redirect for resolving tokens.",
                    "The service stores link mappings in an internal database.",
                    "maximum of 1000 tokens per user"
                  ],
                  "clarificationQuestions": []
                }
                """;
        fakeProvider.setEnabled(true);
        fakeProvider.setResponsePayload(modelResponseWithInventedQuota);

        RequirementInterpretationResult result = agent.interpret(requirement);

        assertThat(result.clear()).isTrue();
        assertThat(result.fallbackOccurred()).isFalse();
        assertThat(result.acceptanceCriteria()).hasSize(4);

        // Verify system prompt instructs model not to invent numeric limits or per-user quotas
        String systemPrompt = fakeProvider.getLastSystemPrompt();
        assertThat(systemPrompt).contains("Do NOT invent numeric limits, per-user quotas");

        // Verify valid non-binding technical defaults are preserved
        assertThat(result.assumptions()).contains(
                "The service uses a standard HTTP 302 redirect for resolving tokens.",
                "The service stores link mappings in an internal database."
        );

        // Verify unsupported per-user quota or limit is NOT included
        assertThat(result.assumptions()).noneMatch(a ->
                a.toLowerCase().contains("per user") ||
                a.toLowerCase().contains("1000 tokens") ||
                a.toLowerCase().contains("quota")
        );
    }
}
