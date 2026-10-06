package com.linkforge.api.dto;

import java.util.Collections;
import java.util.List;

/**
 * Traceability and evidence status for an individual acceptance criterion.
 */
public record CriterionEvidenceItem(
        String criterionId,
        String criterionText,
        String status, // "ANALYZED", "PLANNED", "UNCOVERED"
        List<String> plannedTaskIds,
        List<String> specialistRoles,
        List<SpecialistEvidenceDetail> specialistFindings,
        List<String> relevantEventTypes,
        String eventLinkageStatus,
        boolean hasPersistedEvidence
) {
    public CriterionEvidenceItem {
        plannedTaskIds = plannedTaskIds != null ? List.copyOf(plannedTaskIds) : Collections.emptyList();
        specialistRoles = specialistRoles != null ? List.copyOf(specialistRoles) : Collections.emptyList();
        specialistFindings = specialistFindings != null ? List.copyOf(specialistFindings) : Collections.emptyList();
        relevantEventTypes = relevantEventTypes != null ? List.copyOf(relevantEventTypes) : Collections.emptyList();
    }
}
