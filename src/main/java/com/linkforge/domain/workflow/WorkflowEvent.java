package com.linkforge.domain.workflow;

import java.time.Instant;
import java.util.UUID;

public record WorkflowEvent(
        String eventId,
        String eventType,
        String stage,
        String description,
        Instant timestamp
) {
    public static WorkflowEvent of(String eventType, String stage, String description) {
        return new WorkflowEvent(
                UUID.randomUUID().toString(),
                eventType,
                stage,
                description,
                Instant.now()
        );
    }
}
