package com.linkforge.domain.workflow.scenario;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Bounded, read-only evidence extracted from an inspected brownfield repository.
 */
public record RepositoryEvidence(
        String repositoryPath,
        int totalFiles,
        long totalSizeBytes,
        List<String> detectedLanguages,
        List<String> detectedFrameworks,
        List<String> projectFileNames,
        List<String> sampleSourcePaths,
        Map<String, String> keySnippets,
        String summary
) {
    public RepositoryEvidence {
        detectedLanguages = detectedLanguages != null ? List.copyOf(detectedLanguages) : Collections.emptyList();
        detectedFrameworks = detectedFrameworks != null ? List.copyOf(detectedFrameworks) : Collections.emptyList();
        projectFileNames = projectFileNames != null ? List.copyOf(projectFileNames) : Collections.emptyList();
        sampleSourcePaths = sampleSourcePaths != null ? List.copyOf(sampleSourcePaths) : Collections.emptyList();
        keySnippets = keySnippets != null ? Map.copyOf(keySnippets) : Collections.emptyMap();
    }

    public boolean hasEvidence() {
        return totalFiles > 0 || !projectFileNames.isEmpty() || !detectedLanguages.isEmpty();
    }

    public static RepositoryEvidence none() {
        return new RepositoryEvidence(
                null,
                0,
                0L,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap(),
                "No repository evidence (Greenfield request)."
        );
    }
}
