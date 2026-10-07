package com.linkforge.domain.workflow.implementation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Governed implementation proposal bundling structured file changes, scope designation,
 * and an overall cryptographic proposal hash.
 */
public record ImplementationProposal(
        String proposalId,
        String scope,
        boolean supported,
        String unsupportedReason,
        List<FileChangeProposal> changes,
        String proposalHash,
        Instant proposedAt
) {
    public ImplementationProposal {
        changes = changes != null ? List.copyOf(changes) : Collections.emptyList();
    }

    public static ImplementationProposal supported(
            String scope,
            List<FileChangeProposal> changes
    ) {
        String proposalId = UUID.randomUUID().toString();
        List<FileChangeProposal> safeChanges = changes != null ? List.copyOf(changes) : Collections.emptyList();
        String proposalHash = computeProposalHash(scope, safeChanges);
        return new ImplementationProposal(
                proposalId,
                scope,
                true,
                null,
                safeChanges,
                proposalHash,
                Instant.now()
        );
    }

    public static ImplementationProposal unsupported(
            String requirement,
            String unsupportedReason
    ) {
        String proposalId = UUID.randomUUID().toString();
        return new ImplementationProposal(
                proposalId,
                "UNSUPPORTED",
                false,
                unsupportedReason != null ? unsupportedReason : "Requirement is outside the supported URL-shortener implementation scope.",
                Collections.emptyList(),
                "unsupported",
                Instant.now()
        );
    }

    public static String computeProposalHash(String scope, List<FileChangeProposal> changes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append("scope:").append(scope != null ? scope : "").append(";");
            if (changes != null) {
                for (FileChangeProposal change : changes) {
                    sb.append(change.path()).append(":")
                            .append(change.operation()).append(":")
                            .append(change.expectedInputHash() != null ? change.expectedInputHash() : "").append(":")
                            .append(change.targetHash()).append(":")
                            .append(change.taskLineage()).append(":")
                            .append(change.criterionLineage()).append(";");
                }
            }
            byte[] bytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
