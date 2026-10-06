package com.linkforge.api.dto;

import java.util.List;

/**
 * Specialist findings mapped to an acceptance criterion.
 */
public record SpecialistEvidenceDetail(
        String taskId,
        String agentName,
        String role,
        String status,
        String provider,
        String model,
        boolean fallbackOccurred,
        String fallbackReason,
        List<String> recommendations,
        List<String> testIdeas
) {}
