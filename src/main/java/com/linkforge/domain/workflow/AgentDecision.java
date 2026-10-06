package com.linkforge.domain.workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record AgentDecision(
        String decisionId,
        String agentName,
        String agentType,
        String decision,
        String rationale,
        Map<String, Object> metadata,
        Instant timestamp
) {
    public static final String DETERMINISTIC_SPECIALIST = "DETERMINISTIC_SPECIALIST";

    public static AgentDecision of(
            String agentName,
            String decision,
            String rationale,
            Map<String, Object> metadata
    ) {
        String type = DETERMINISTIC_SPECIALIST;
        if (metadata != null && metadata.containsKey("type")) {
            type = String.valueOf(metadata.get("type"));
        }
        return of(agentName, type, decision, rationale, metadata);
    }

    public static AgentDecision of(
            String agentName,
            String agentType,
            String decision,
            String rationale,
            Map<String, Object> metadata
    ) {
        return new AgentDecision(
                UUID.randomUUID().toString(),
                agentName,
                agentType != null ? agentType : DETERMINISTIC_SPECIALIST,
                decision,
                rationale,
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap(),
                Instant.now()
        );
    }
}
