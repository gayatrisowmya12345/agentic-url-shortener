package com.linkforge.domain.workflow.specialist;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility for extracting stable acceptance criteria IDs (e.g., AC-1, AC-BROWNFIELD)
 * or deterministically assigning indexed IDs when criteria lack prefixes.
 */
public final class SpecialistCriteriaMapper {

    private SpecialistCriteriaMapper() {}

    public static String extractOrAssignId(String criterion, int index) {
        if (criterion == null || criterion.isBlank()) {
            return "AC-" + (index + 1);
        }
        String trimmed = criterion.trim();
        if (trimmed.matches("^[A-Za-z0-9_-]+$")) {
            return trimmed;
        }
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0 && colonIdx <= 20) {
            String prefix = trimmed.substring(0, colonIdx).trim();
            if (prefix.matches("^[A-Za-z0-9_-]+$")) {
                return prefix;
            }
        }
        int dashIdx = trimmed.indexOf(" - ");
        if (dashIdx > 0 && dashIdx <= 20) {
            String prefix = trimmed.substring(0, dashIdx).trim();
            if (prefix.matches("^[A-Za-z0-9_-]+$")) {
                return prefix;
            }
        }
        return "AC-" + (index + 1);
    }

    public static Map<String, String> mapCriteria(List<String> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < criteria.size(); i++) {
            String id = extractOrAssignId(criteria.get(i), i);
            if (map.containsKey(id)) {
                id = id + "-" + (i + 1);
            }
            map.put(id, criteria.get(i));
        }
        return Collections.unmodifiableMap(map);
    }

    public static Set<String> validIds(List<String> criteria) {
        return mapCriteria(criteria).keySet();
    }
}
