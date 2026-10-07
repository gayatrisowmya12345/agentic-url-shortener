package com.linkforge.domain.workflow.implementation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Verified rollback outcome restoring pre-mutation file state in the isolated workspace.
 */
public record RollbackResult(
        boolean success,
        List<String> restoredFiles,
        Map<String, String> restoredHashes,
        String reason,
        Instant rolledBackAt
) {
    public RollbackResult {
        restoredFiles = restoredFiles != null ? List.copyOf(restoredFiles) : Collections.emptyList();
        restoredHashes = restoredHashes != null ? Map.copyOf(restoredHashes) : Collections.emptyMap();
    }
}
