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
public class DataPersistenceSpecialistAgent extends BaseSpecialistAgent {

    public static final String AGENT_NAME = "data-persistence-specialist";

    @Autowired
    public DataPersistenceSpecialistAgent(@Autowired(required = false) LlmModelProvider llmModelProvider, ObjectMapper objectMapper) {
        super(llmModelProvider, objectMapper);
    }

    @Override
    public SpecialistRole getRole() {
        return SpecialistRole.DATA_PERSISTENCE;
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    @Override
    protected String getSystemPrompt() {
        return """
                You are the Data and Persistence Specialist agent in the LinkForge engineering workbench.
                Your role is to analyze entity models, relational schemas, database constraints, and persistence safety.
                Specialization focus:
                - Relational schema design (links table, click_events table)
                - Unique index constraints on generated tokens and custom aliases
                - Atomic click counter increments and transactional consistency
                - Thread safety under concurrent link creation and redirects
                - Storage engine abstraction (H2 embedded database and Spring JdbcTemplate)

                You must return EXACTLY one JSON object matching this schema:
                {
                  "summary": "Concise technical persistence analysis (max 500 chars)",
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
                "Establish embedded H2 relational schema with dedicated tables: 'links' (token, destination_url, custom_alias, click_count, created_at) and 'click_events' (id, link_id, clicked_at).",
                "Apply unique database constraints on token and custom_alias to guarantee data integrity across concurrent requests.",
                "Use Spring JdbcTemplate with atomic update queries ('UPDATE links SET click_count = click_count + 1 WHERE token = ?') to prevent race conditions."
        );

        List<String> testIdeas = List.of(
                "Verify database constraint enforcement rejecting duplicate token or alias insertion.",
                "Test concurrent redirect clicks asserting accurate click count tally in H2 database.",
                "Validate entity mapping and timestamp recording for generated short link records."
        );

        Map<String, String> criteriaMap = com.linkforge.domain.workflow.specialist.SpecialistCriteriaMapper.mapCriteria(input.acceptanceCriteria());
        List<String> addressed = new ArrayList<>();
        for (Map.Entry<String, String> entry : criteriaMap.entrySet()) {
            String text = entry.getValue().toLowerCase();
            if (text.contains("data") || text.contains("persist") || text.contains("store") || text.contains("h2") || text.contains("database") || text.contains("schema") || text.contains("table") || text.contains("click")) {
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

        String summary = "Data Persistence analysis for " + input.taskId() + " (" + input.taskTitle() +
                "): Structured relational constraints and thread-safe persistence guarantees.";

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
