package com.linkforge.domain.workflow.implementation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Structured change proposal for a single file, maintaining strict acceptance criterion
 * and task lineage, operation type, and cryptographic content hashes.
 */
public record FileChangeProposal(
        String path,
        FileChangeOperation operation,
        String proposedContent,
        String expectedInputHash,
        String targetHash,
        String taskLineage,
        String criterionLineage,
        String specialistRole,
        String description
) {
    public static FileChangeProposal of(
            String path,
            FileChangeOperation operation,
            String proposedContent,
            String expectedInputHash,
            String taskLineage,
            String criterionLineage,
            String specialistRole,
            String description
    ) {
        String targetHash = computeHash(proposedContent != null ? proposedContent : "");
        return new FileChangeProposal(
                path,
                operation,
                proposedContent,
                expectedInputHash,
                targetHash,
                taskLineage,
                criterionLineage,
                specialistRole,
                description
        );
    }

    public static String computeHash(String content) {
        if (content == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
