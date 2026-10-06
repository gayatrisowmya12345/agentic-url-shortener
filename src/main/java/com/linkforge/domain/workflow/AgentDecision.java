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
        return new AgentDecision(
                UUID.randomUUID().toString(),
                agentName,
                DETERMINISTIC_SPECIALIST,
                decision,
                rationale,
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap(),
                Instant.now()
        );
    }
}
