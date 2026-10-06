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
public class SecurityValidationSpecialistAgent extends BaseSpecialistAgent {

    public static final String AGENT_NAME = "security-validation-specialist";

    @Autowired
    public SecurityValidationSpecialistAgent(@Autowired(required = false) LlmModelProvider llmModelProvider, ObjectMapper objectMapper) {
        super(llmModelProvider, objectMapper);
    }

    @Override
    public SpecialistRole getRole() {
        return SpecialistRole.SECURITY_VALIDATION;
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    @Override
    protected String getSystemPrompt() {
        return """
                You are the Security and Validation Specialist agent in the LinkForge engineering workbench.
                Your role is to analyze input sanitation, URL scheme verification, alias safety, and attack mitigations.
                Specialization focus:
                - Destination URL validation: strict HTTP and HTTPS whitelist (reject javascript:, file:, data:)
                - RFC 3986 URI format compliance and scheme validation
                - Custom alias character set bounds ([a-zA-Z0-9_-], min 3, max 30 chars)
                - Rejection of path traversal sequences, shell control characters, and malformed tokens
                - Defense against open redirect abuse and injection attacks

                You must return EXACTLY one JSON object matching this schema:
                {
                  "summary": "Concise technical security analysis (max 500 chars)",
                  "recommendations": [
                    "Recommendation 1 (max 300 chars)",
                    "Recommendation 2 (max 300 chars)"
                  ],
                  "testIdeas": [
                    "Test scenario 1 (max 300 chars)",
                    "Test scenario 2 (max 300 chars)"
                  ],
                  "addressedCriteria": ["AC-1", "AC-2"]
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
                "Enforce strict protocol whitelisting: only 'http' and 'https' schemes are accepted; immediately reject 'file:', 'javascript:', and 'data:' URIs.",
                "Validate custom aliases against regex pattern '^[a-zA-Z0-9_-]{3,30}$' to avoid path collisions and injection attempts.",
                "Sanitize redirect target headers to protect against HTTP response splitting and header injection."
        );

        List<String> testIdeas = List.of(
                "Test rejection of malicious URI schemes (javascript:alert(1), file:///etc/passwd) with HTTP 400 Bad Request.",
                "Assert custom alias with spaces, emojis, or punctuation is rejected with clear validation error.",
                "Verify null, empty, or whitespace-only destination URLs return 400 Bad Request."
        );

        Map<String, String> criteriaMap = com.linkforge.domain.workflow.specialist.SpecialistCriteriaMapper.mapCriteria(input.acceptanceCriteria());
        List<String> addressed = new ArrayList<>();
        for (Map.Entry<String, String> entry : criteriaMap.entrySet()) {
            String text = entry.getValue().toLowerCase();
            if (text.contains("security") || text.contains("valid") || text.contains("sanit") || text.contains("protocol") || text.contains("scheme") || text.contains("auth") || text.contains("whitelist")) {
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

        String summary = "Security Validation analysis for " + input.taskId() + " (" + input.taskTitle() +
                "): Hardened protocol validation and bounded input sanitization.";

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
