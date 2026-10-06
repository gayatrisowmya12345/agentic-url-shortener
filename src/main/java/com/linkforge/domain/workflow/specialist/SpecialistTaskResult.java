package com.linkforge.domain.workflow.specialist;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record SpecialistTaskResult(
        String taskId,
        SpecialistRole role,
        String agentName,
        String status, // "SUCCESS", "FAILED"
        String summary,
        List<String> recommendations,
        List<String> testIdeas,
        List<String> addressedCriteria,
        Map<String, Object> metadata,
        boolean fallbackOccurred,
        String fallbackReason,
        Instant startedAt,
        Instant completedAt
) {
    public SpecialistTaskResult {
        recommendations = recommendations != null ? List.copyOf(recommendations) : Collections.emptyList();
        testIdeas = testIdeas != null ? List.copyOf(testIdeas) : Collections.emptyList();
        addressedCriteria = addressedCriteria != null ? List.copyOf(addressedCriteria) : Collections.emptyList();
        metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
    }
}
