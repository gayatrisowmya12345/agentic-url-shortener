package com.linkforge.domain.workflow.specialist;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record SpecialistInvocation(
        String invocationId,
        String taskId,
        String agentName,
        String role,
        String status, // "SUCCESS", "FAILED"
        String inputSummary,
        String outputSummary,
        List<String> recommendations,
        List<String> testIdeas,
        List<String> addressedCriteria,
        String provider,
        String model,
        boolean fallbackOccurred,
        String fallbackReason,
        Instant startedAt,
        Instant completedAt
) {
    public SpecialistInvocation {
        recommendations = recommendations != null ? List.copyOf(recommendations) : Collections.emptyList();
        testIdeas = testIdeas != null ? List.copyOf(testIdeas) : Collections.emptyList();
        addressedCriteria = addressedCriteria != null ? List.copyOf(addressedCriteria) : Collections.emptyList();
    }

    public static SpecialistInvocation fromResult(SpecialistTaskResult result, String inputSummary) {
        String invocationId = UUID.randomUUID().toString();
        String provider = result.metadata() != null ? String.valueOf(result.metadata().getOrDefault("provider", "deterministic")) : "deterministic";
        String model = result.metadata() != null ? String.valueOf(result.metadata().getOrDefault("model", "rules")) : "rules";

        return new SpecialistInvocation(
                invocationId,
                result.taskId(),
                result.agentName(),
                result.role() != null ? result.role().name() : "SPECIALIST",
                result.status(),
                inputSummary,
                result.summary(),
                result.recommendations(),
                result.testIdeas(),
                result.addressedCriteria(),
                provider,
                model,
                result.fallbackOccurred(),
                result.fallbackReason(),
                result.startedAt(),
                result.completedAt()
        );
    }
}
