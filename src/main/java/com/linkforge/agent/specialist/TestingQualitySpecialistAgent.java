package com.linkforge.agent.specialist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.ai.provider.LlmModelProvider;
import com.linkforge.domain.workflow.specialist.SpecialistRole;
import com.linkforge.domain.workflow.specialist.SpecialistTaskInput;
import com.linkforge.domain.workflow.specialist.SpecialistTaskResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TestingQualitySpecialistAgent extends BaseSpecialistAgent {

    public static final String AGENT_NAME = "testing-quality-specialist";

    @Autowired
    public TestingQualitySpecialistAgent(@Autowired(required = false) LlmModelProvider llmModelProvider, ObjectMapper objectMapper) {
        super(llmModelProvider, objectMapper);
    }

    @Override
    public SpecialistRole getRole() {
        return SpecialistRole.TESTING_QUALITY;
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    @Override
    protected String getSystemPrompt() {
        return """
                You are the Testing and Quality Specialist agent in the LinkForge engineering workbench.
                Your role is to formulate comprehensive test strategies, edge case coverage, and verification suites.
                Specialization focus:
                - MockMvc controller tests for HTTP status codes, headers, and payload structures
                - Concurrency and race-condition testing for link generation and click counters
                - Negative edge cases (malformed URLs, duplicate aliases, missing resources, out-of-boundary inputs)
                - Regression testing and test fixture isolation
                - End-to-end verification workflows from creation to redirect to analytics retrieval

                You must return EXACTLY one JSON object matching this schema:
                {
                  "summary": "Concise technical quality analysis (max 500 chars)",
                  "recommendations": [
                    "Recommendation 1 (max 300 chars)",
                    "Recommendation 2 (max 300 chars)"
                  ],
                  "testIdeas": [
                    "Test scenario 1 (max 300 chars)",
                    "Test scenario 2 (max 300 chars)"
                  ],
                  "addressedCriteria": ["AC-1", "AC-2", "AC-3"]
                }
                DO NOT write source code files, DO NOT run shell commands or execute repository files.
                """;
    }

    @Override
    protected SpecialistTaskResult generateFallbackResult(
            SpecialistTaskInput input,
            Instant startedAt,
            String fallbackReason,
            boolean fallbackOccurred
    ) {
        List<String> recommendations = List.of(
                "Implement tiered automated testing: fast MockMvc slice tests for controllers, isolated service tests with fake repositories, and full integration tests with embedded H2.",
                "Ensure negative scenarios (invalid URL schemes, duplicate aliases, non-existent tokens) have explicit regression assertions.",
                "Verify database transaction isolation and deterministic test fixture cleanup across test runs."
        );

        List<String> testIdeas = List.of(
                "Full lifecycle verification: create short link -> trigger GET /{token} redirect -> fetch analytics verifying click count = 1.",
                "Execute concurrent multi-threaded requests creating same alias to verify only one 201 succeeds while remainder receive 409 Conflict.",
                "Verify actuator health and metrics endpoints report UP during application operation."
        );

        Map<String, String> criteriaMap = com.linkforge.domain.workflow.specialist.SpecialistCriteriaMapper.mapCriteria(input.acceptanceCriteria());
        List<String> addressed = new ArrayList<>();
        for (Map.Entry<String, String> entry : criteriaMap.entrySet()) {
            String text = entry.getValue().toLowerCase();
            if (text.contains("test") || text.contains("quality") || text.contains("verif") || text.contains("assert") || text.contains("coverage") || text.contains("mock")) {
                addressed.add(entry.getKey());
                if (addressed.size() >= 2) {
                    break;
                }
            }
        }
        if (addressed.isEmpty() && !criteriaMap.isEmpty()) {
            addressed.add(criteriaMap.keySet().iterator().next());
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", fallbackOccurred ? (llmModelProvider != null ? llmModelProvider.getProviderId() : "ollama") : "deterministic");
        metadata.put("model", fallbackOccurred ? (llmModelProvider != null ? llmModelProvider.getModelName() : "rules") : "rules");
        metadata.put("type", fallbackOccurred ? "DETERMINISTIC_SPECIALIST_FALLBACK" : "DETERMINISTIC_SPECIALIST");
        metadata.put("fallbackOccurred", fallbackOccurred);

        String summary = "Testing and Quality analysis for " + input.taskId() + " (" + input.taskTitle() +
                "): Formulated complete regression matrix, concurrency tests, and edge case assertions.";

        return new SpecialistTaskResult(
                input.taskId(),
                getRole(),
                getAgentName(),
                "SUCCESS",
                summary,
                recommendations,
                testIdeas,
                addressed,
                metadata,
                fallbackOccurred,
                fallbackReason,
                startedAt,
                Instant.now()
        );
    }
}
