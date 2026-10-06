package com.linkforge.api.dto;

import java.util.List;

/**
 * Traceability status for an individual planned task and its specialist execution.
 */
public record TaskTraceabilityItem(
        String taskId,
        String title,
        String status,
        String specialistRole,
        List<String> dependencies,
        List<String> addressedCriteria,
        boolean specialistExecuted,
        String specialistAgentName,
        String specialistStatus
) {}
