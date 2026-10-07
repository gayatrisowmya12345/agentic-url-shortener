package com.linkforge.service.implementation;

import com.linkforge.domain.workflow.implementation.AppliedFileChange;
import com.linkforge.domain.workflow.implementation.FileChangeOperation;
import com.linkforge.domain.workflow.implementation.FileChangeProposal;
import com.linkforge.domain.workflow.implementation.RollbackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies controlled patch changes inside an isolated workspace while enforcing
 * path containment, allowed file extensions, operation and byte bounds, duplicate-path
 * rejection, optimistic hash checks, atomic writes, and verified rollback.
 */
@Component
public class GovernedPatchApplier {

    private static final Logger log = LoggerFactory.getLogger(GovernedPatchApplier.class);

    private final GovernedExecutionProperties properties;

    @Autowired
    public GovernedPatchApplier(GovernedExecutionProperties properties) {
        this.properties = properties != null ? properties : new GovernedExecutionProperties();
    }

    public record ApplicationResult(
            List<AppliedFileChange> appliedChanges,
            Map<Path, byte[]> originalSnapshots
    ) {}

    public ApplicationResult applyChanges(Path workspaceRoot, List<FileChangeProposal> changes) throws IOException {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            throw new IllegalArgumentException("Workspace root must be an existing directory.");
        }
        if (changes == null || changes.isEmpty()) {
            return new ApplicationResult(List.of(), Map.of());
        }

        // 1. Operation Count Limit
        if (changes.size() > properties.getMaxOperations()) {
            throw new SafetyPolicyViolationException("Proposal operation count (" + changes.size() +
                    ") exceeds configured limit of " + properties.getMaxOperations() + ".");
        }

        // 2. Validate Duplicate Paths and Total Bytes
        Set<String> seenPaths = new HashSet<>();
        long totalBytes = 0;

        for (FileChangeProposal change : changes) {
            String path = change.path();
            if (path == null || path.isBlank()) {
                throw new SafetyPolicyViolationException("Proposal contains a null or blank file path.");
            }
            String normalizedPathStr = path.replace('\\', '/').trim();
            if (!seenPaths.add(normalizedPathStr)) {
                throw new SafetyPolicyViolationException("Proposal contains duplicate change for path: '" + path + "'.");
            }

            // Path containment check (relative, no '..' traversal, allowed extension)
            validatePathSafety(workspaceRoot, normalizedPathStr);

            // Byte size check
            byte[] contentBytes = change.proposedContent() != null
                    ? change.proposedContent().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];

            if (contentBytes.length > properties.getMaxFileSizeBytes()) {
                throw new SafetyPolicyViolationException("File '" + path + "' size (" + contentBytes.length +
                        " bytes) exceeds max file size limit of " + properties.getMaxFileSizeBytes() + " bytes.");
            }
            totalBytes += contentBytes.length;
        }

        if (totalBytes > properties.getMaxTotalChangeBytes()) {
            throw new SafetyPolicyViolationException("Total change size (" + totalBytes +
                    " bytes) exceeds max proposal limit of " + properties.getMaxTotalChangeBytes() + " bytes.");
        }

        // 3. Snapshot existing files for rollback and verify optimistic hashes
        Map<Path, byte[]> originalSnapshots = new HashMap<>();
        Path realWorkspaceRoot = workspaceRoot.toRealPath();

        for (FileChangeProposal change : changes) {
            Path targetPath = realWorkspaceRoot.resolve(change.path()).normalize();

            if (change.operation() == FileChangeOperation.CREATE) {
                if (Files.exists(targetPath)) {
                    throw new SafetyPolicyViolationException("Cannot CREATE file that already exists: '" + change.path() + "'.");
                }
                // File does not exist yet; snapshot as null so rollback can delete it
                originalSnapshots.put(targetPath, null);

            } else if (change.operation() == FileChangeOperation.MODIFY) {
                if (change.expectedInputHash() == null || change.expectedInputHash().isBlank()) {
                    throw new SafetyPolicyViolationException("Operation MODIFY requires a non-blank expectedInputHash for file: '" + change.path() + "'.");
                }
                if (!Files.exists(targetPath)) {
                    throw new SafetyPolicyViolationException("Cannot MODIFY file that does not exist: '" + change.path() + "'.");
                }
                byte[] existingBytes = Files.readAllBytes(targetPath);
                String actualHash = computeSha256(existingBytes);
                if (!actualHash.equalsIgnoreCase(change.expectedInputHash().trim())) {
                    throw new StaleInputHashException("Optimistic hash check failed for '" + change.path() +
                            "': expected hash " + change.expectedInputHash().trim() +
                            " but found current hash " + actualHash + ".");
                }
                originalSnapshots.put(targetPath, existingBytes);

            } else if (change.operation() == FileChangeOperation.DELETE) {
                if (change.expectedInputHash() == null || change.expectedInputHash().isBlank()) {
                    throw new SafetyPolicyViolationException("Operation DELETE requires a non-blank expectedInputHash for file: '" + change.path() + "'.");
                }
                if (!Files.exists(targetPath)) {
                    throw new SafetyPolicyViolationException("Cannot DELETE file that does not exist: '" + change.path() + "'.");
                }
                byte[] existingBytes = Files.readAllBytes(targetPath);
                String actualHash = computeSha256(existingBytes);
                if (!actualHash.equalsIgnoreCase(change.expectedInputHash().trim())) {
                    throw new StaleInputHashException("Optimistic hash check failed for '" + change.path() +
                            "': expected hash " + change.expectedInputHash().trim() +
                            " but found current hash " + actualHash + ".");
                }
                originalSnapshots.put(targetPath, existingBytes);
            }
        }

        // 4. Atomic Application
        List<AppliedFileChange> applied = new ArrayList<>();
        try {
            for (FileChangeProposal change : changes) {
                Path targetPath = realWorkspaceRoot.resolve(change.path()).normalize();
                String originalHash = Files.exists(targetPath) ? computeSha256(Files.readAllBytes(targetPath)) : null;

                if (change.operation() == FileChangeOperation.DELETE) {
                    Files.deleteIfExists(targetPath);
                    applied.add(new AppliedFileChange(change.path(), FileChangeOperation.DELETE, originalHash, null, Instant.now()));
                } else {
                    // Atomic write via temp file
                    byte[] contentBytes = change.proposedContent() != null
                            ? change.proposedContent().getBytes(StandardCharsets.UTF_8)
                            : new byte[0];

                    Path parentDir = targetPath.getParent();
                    if (parentDir != null && !Files.exists(parentDir)) {
                        Files.createDirectories(parentDir);
                    }

                    Path tempFile = Files.createTempFile(parentDir != null ? parentDir : realWorkspaceRoot,
                            ".linkforge-tmp-", "-" + UUID.randomUUID());
                    try {
                        Files.write(tempFile, contentBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }

                    String newHash = computeSha256(contentBytes);
                    applied.add(new AppliedFileChange(change.path(), change.operation(), originalHash, newHash, Instant.now()));
                }
            }
        } catch (Exception ex) {
            log.error("Error during atomic patch application; triggering immediate rollback: {}", ex.getMessage());
            rollback(originalSnapshots, "Application interrupted: " + ex.getMessage());
            throw ex;
        }

        return new ApplicationResult(applied, originalSnapshots);
    }

    public RollbackResult rollback(Map<Path, byte[]> originalSnapshots, String reason) {
        if (originalSnapshots == null || originalSnapshots.isEmpty()) {
            return new RollbackResult(true, List.of(), Map.of(), reason, Instant.now());
        }

        List<String> restoredFiles = new ArrayList<>();
        Map<String, String> restoredHashes = new HashMap<>();
        boolean allSuccess = true;

        for (Map.Entry<Path, byte[]> entry : originalSnapshots.entrySet()) {
            Path path = entry.getKey();
            byte[] originalBytes = entry.getValue();
            try {
                if (originalBytes == null) {
                    // File didn't exist before; delete it
                    Files.deleteIfExists(path);
                    restoredFiles.add(path.toString());
                    restoredHashes.put(path.toString(), "DELETED");
                } else {
                    // Restore original bytes atomically
                    Path parent = path.getParent();
                    if (parent != null && !Files.exists(parent)) {
                        Files.createDirectories(parent);
                    }
                    Path tempFile = Files.createTempFile(parent, ".rollback-tmp-", ".tmp");
                    try {
                        Files.write(tempFile, originalBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }

                    // Verify restored hash
                    String currentHash = computeSha256(Files.readAllBytes(path));
                    String expectedHash = computeSha256(originalBytes);
                    if (currentHash.equalsIgnoreCase(expectedHash)) {
                        restoredFiles.add(path.toString());
                        restoredHashes.put(path.toString(), currentHash);
                    } else {
                        allSuccess = false;
                        log.error("Rollback hash verification mismatch for file {}", path);
                    }
                }
            } catch (Exception ex) {
                allSuccess = false;
                log.error("Failed to restore file during rollback {}: {}", path, ex.getMessage(), ex);
            }
        }

        return new RollbackResult(allSuccess, restoredFiles, restoredHashes, reason, Instant.now());
    }

    private void validatePathSafety(Path workspaceRoot, String relativePathStr) throws IOException {
        if (relativePathStr.startsWith("/") || relativePathStr.startsWith("\\")
                || (relativePathStr.length() > 2 && relativePathStr.charAt(1) == ':')) {
            throw new SafetyPolicyViolationException("Absolute paths are not allowed: '" + relativePathStr + "'.");
        }
        if (relativePathStr.contains("..") || relativePathStr.contains("/./") || relativePathStr.contains("\\.\\")) {
            throw new SafetyPolicyViolationException("Directory traversal sequences are rejected: '" + relativePathStr + "'.");
        }

        // Allowed extension check
        boolean hasAllowedExtension = properties.getAllowedExtensions().stream()
                .anyMatch(ext -> relativePathStr.toLowerCase().endsWith(ext.toLowerCase()));
        if (!hasAllowedExtension) {
            throw new SafetyPolicyViolationException("Disallowed file extension for path '" + relativePathStr +
                    "'. Allowed extensions: " + properties.getAllowedExtensions());
        }

        // Check path containment relative to real workspace root
        Path realWorkspaceRoot = workspaceRoot.toRealPath();
        Path resolved = realWorkspaceRoot.resolve(relativePathStr).normalize();
        if (!resolved.startsWith(realWorkspaceRoot)) {
            throw new SafetyPolicyViolationException("Path escapes workspace boundary: '" + relativePathStr + "'.");
        }
    }

    public static String computeSha256(byte[] data) {
        if (data == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
