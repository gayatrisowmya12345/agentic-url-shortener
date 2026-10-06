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
public class ApiBehaviorSpecialistAgent extends BaseSpecialistAgent {

    public static final String AGENT_NAME = "api-behavior-specialist";

    @Autowired
    public ApiBehaviorSpecialistAgent(@Autowired(required = false) LlmModelProvider llmModelProvider, ObjectMapper objectMapper) {
        super(llmModelProvider, objectMapper);
    }

    @Override
    public SpecialistRole getRole() {
        return SpecialistRole.API_BEHAVIOR;
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    @Override
    protected String getSystemPrompt() {
        return """
                You are the API and Behavior Specialist agent in the LinkForge engineering workbench.
                Your role is to analyze HTTP REST contracts, endpoint behaviors, status codes, and user-facing routing.
                Specialization focus:
                - POST /api/v1/links contract and response schemas
                - GET /{token} HTTP 302 redirection behaviors
                - GET /api/v1/links/{token}/analytics data retrieval
                - Custom alias handling and HTTP 409 Conflict semantics
                - Error responses (400 Bad Request, 404 Not Found)

                You must return EXACTLY one JSON object matching this schema:
                {
                  "summary": "Concise technical behavior analysis (max 500 chars)",
                  "recommendations": [
                    "Recommendation 1 (max 300 chars)",
                    "Recommendation 2 (max 300 chars)"
                  ],
                  "testIdeas": [
                    "Test scenario 1 (max 300 chars)",
                    "Test scenario 2 (max 300 chars)"
                  ],
                  "addressedCriteria": ["AC-1", "AC-3"]
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
                "Expose RESTful endpoints with consistent JSON payloads and HTTP status semantics (201 Created, 302 Found, 400 Bad Request, 404 Not Found, 409 Conflict).",
                "Ensure GET /{token} issues HTTP 302 Found with strict Location header pointing to validated original destination URL.",
                "Enforce atomic click event recording during redirection without blocking response latency."
        );

        List<String> testIdeas = List.of(
                "Verify HTTP 302 redirect sets correct Location header and records visit event.",
                "Assert 409 Conflict when submitting already-registered custom alias.",
                "Verify 404 Not Found for non-existent token or unknown alias lookup."
        );

        Map<String, String> criteriaMap = com.linkforge.domain.workflow.specialist.SpecialistCriteriaMapper.mapCriteria(input.acceptanceCriteria());
        List<String> addressed = new ArrayList<>();
        for (Map.Entry<String, String> entry : criteriaMap.entrySet()) {
            String text = entry.getValue().toLowerCase();
            boolean isPersistence = text.contains("persist") || text.contains("database") || text.contains("h2") || text.contains("schema");
            if (!isPersistence && (text.contains("api") || text.contains("endpoint") || text.contains("redirect") || text.contains("http") || text.contains("controller") || text.contains("302") || text.contains("alias") || text.contains("link"))) {
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

        String summary = "API Behavior analysis for " + input.taskId() + " (" + input.taskTitle() +
                "): Enforced RFC-compliant HTTP status contracts and atomic redirection semantics.";

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
