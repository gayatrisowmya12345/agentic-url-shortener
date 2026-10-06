package com.linkforge.service.inspection;

import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.exception.InvalidRepositoryPathException;
import com.linkforge.domain.workflow.scenario.exception.PathTraversalException;
import com.linkforge.domain.workflow.scenario.exception.RepositoryLimitExceededException;
import com.linkforge.domain.workflow.scenario.exception.SymlinkEscapeException;
import com.linkforge.domain.workflow.scenario.exception.UnsupportedFileTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Safe, read-only codebase inspector for brownfield workflow analysis.
 * Enforces relative-path requirement under approved root, repository-boundary symlink confinement,
 * pre-read file type validation, and configurable resource limits without executing any files or commands.
 */
@Component
public class CodebaseInspector {

    private static final Logger log = LoggerFactory.getLogger(CodebaseInspector.class);

    private final CodebaseInspectionProperties properties;

    public CodebaseInspector(CodebaseInspectionProperties properties) {
        this.properties = properties != null ? properties : new CodebaseInspectionProperties();
    }

    public RepositoryEvidence inspect(String repositoryPathString) {
        if (repositoryPathString == null || repositoryPathString.isBlank()) {
            throw new InvalidRepositoryPathException("Repository path cannot be null or blank");
        }

        Path approvedRoot = resolveApprovedRoot();
        Path repoPath = resolveAndValidatePath(repositoryPathString, approvedRoot);

        int[] counters = new int[]{0}; // fileCount
        long[] totalSize = new long[]{0L};
        List<String> projectFiles = new ArrayList<>();
        Set<String> languages = new TreeSet<>();
        Set<String> frameworks = new TreeSet<>();
        List<String> samplePaths = new ArrayList<>();
        Map<String, String> keySnippets = new LinkedHashMap<>();

        try {
            Files.walkFileTree(repoPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    // Check directory symlink escape outside the selected repository
                    if (Files.isSymbolicLink(dir)) {
                        validateSymlinkTarget(dir, repoPath);
                    }

                    // Skip .git internal objects to keep evidence focused
                    if (dir.getFileName() != null && ".git".equals(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();

                    // 1. Check file type BEFORE reading file contents or sizing
                    validateFileType(fileName);

                    // 2. Check file symlink escape outside the selected repository
                    boolean isSymlink = Files.isSymbolicLink(file);
                    if (isSymlink) {
                        validateSymlinkTarget(file, repoPath);
                    }

                    // 3. Count limits
                    counters[0]++;
                    if (counters[0] > properties.getMaxFileCount()) {
                        throw new RepositoryLimitExceededException(
                                "Configured file count limit exceeded (maximum allowed: " + properties.getMaxFileCount() + ")"
                        );
                    }

                    long size = attrs.size();
                    if (size > properties.getMaxFileSizeBytes()) {
                        throw new RepositoryLimitExceededException(
                                "File size limit exceeded for " + fileName + " (" + size + " bytes > " + properties.getMaxFileSizeBytes() + " bytes)"
                        );
                    }

                    totalSize[0] += size;
                    if (totalSize[0] > properties.getMaxTotalSizeBytes()) {
                        throw new RepositoryLimitExceededException(
                                "Total repository size limit exceeded (maximum allowed: " + properties.getMaxTotalSizeBytes() + " bytes)"
                        );
                    }

                    // 4. Record sample relative path
                    String relativePath = repoPath.relativize(file).toString();
                    if (samplePaths.size() < properties.getMaxEvidenceFiles()) {
                        samplePaths.add(relativePath);
                    }

                    // 5. Extract evidence without following symlinks
                    if (!isSymlink) {
                        inspectFileEvidence(file, fileName, relativePath, languages, frameworks, projectFiles, keySnippets);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new InvalidRepositoryPathException("I/O error during safe repository traversal: " + e.getMessage(), e);
        }

        String summary = String.format(
                "Inspected repository at '%s': %d files (%d bytes), languages: %s, frameworks: %s, manifests: %s.",
                repoPath.getFileName() != null ? repoPath.getFileName().toString() : repositoryPathString,
                counters[0],
                totalSize[0],
                languages.isEmpty() ? "[none detected]" : String.join(", ", languages),
                frameworks.isEmpty() ? "[none detected]" : String.join(", ", frameworks),
                projectFiles.isEmpty() ? "[none detected]" : String.join(", ", projectFiles)
        );

        log.info("Safe codebase inspection completed for '{}': {} files, languages {}", repositoryPathString, counters[0], languages);

        return new RepositoryEvidence(
                repoPath.getFileName() != null ? repoPath.getFileName().toString() : repositoryPathString,
                counters[0],
                totalSize[0],
                new ArrayList<>(languages),
                new ArrayList<>(frameworks),
                projectFiles,
                samplePaths,
                keySnippets,
                summary
        );
    }

    private Path resolveApprovedRoot() {
        try {
            Path root = Path.of(properties.getApprovedRoot()).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                Files.createDirectories(root);
            }
            return root.toRealPath();
        } catch (IOException e) {
            throw new InvalidRepositoryPathException("Failed to canonicalize approved root directory.", e);
        }
    }

    private Path resolveAndValidatePath(String pathString, Path approvedRoot) {
        Path input = Path.of(pathString);

        // 1. Reject absolute paths - repository paths must be relative to the approved root
        if (input.isAbsolute()) {
            throw new InvalidRepositoryPathException("Absolute repository paths are rejected. Repository paths must be relative to the approved root.");
        }

        // 2. Reject directory traversal attempts
        if (pathString.contains("..")) {
            throw new PathTraversalException("Directory traversal sequence detected in repository path: " + pathString);
        }

        Path resolved = approvedRoot.resolve(input).normalize();

        // 3. Reject paths that escape the approved root
        if (!resolved.startsWith(approvedRoot)) {
            throw new PathTraversalException("Repository path escapes approved root boundary: " + pathString);
        }

        if (!Files.exists(resolved)) {
            throw new InvalidRepositoryPathException("Repository path does not exist: " + pathString);
        }
        if (!Files.isDirectory(resolved)) {
            throw new InvalidRepositoryPathException("Repository path is not a directory: " + pathString);
        }

        Path realPath;
        try {
            realPath = resolved.toRealPath();
        } catch (IOException e) {
            throw new InvalidRepositoryPathException("Failed to canonicalize repository path: " + pathString, e);
        }

        if (!realPath.startsWith(approvedRoot)) {
            throw new PathTraversalException("Repository path escapes approved root boundary: " + pathString);
        }

        return realPath;
    }

    private void validateSymlinkTarget(Path link, Path repoPath) {
        try {
            Path target = link.toRealPath();
            // A symlink target elsewhere under the approved root still counts as outside the selected repository
            if (!target.startsWith(repoPath)) {
                throw new SymlinkEscapeException(
                        "Symbolic link escapes selected repository boundary: " + (link.getFileName() != null ? link.getFileName() : link) + " points outside repository"
                );
            }
        } catch (IOException e) {
            throw new SymlinkEscapeException("Symbolic link target cannot be safely resolved: " + (link.getFileName() != null ? link.getFileName() : link), e);
        }
    }

    private void validateFileType(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String extension = extractExtension(lowerName);

        if (properties.getUnsupportedExtensions().contains(extension) || isKnownDangerousBinary(lowerName)) {
            throw new UnsupportedFileTypeException("Unsupported or dangerous file type rejected: " + fileName);
        }

        // Must be in supported list or known extensionless build files (like Dockerfile)
        if (!properties.getSupportedExtensions().contains(extension) && !"dockerfile".equals(lowerName)) {
            throw new UnsupportedFileTypeException("Unsupported file type not permitted in brownfield inspection: " + fileName);
        }
    }

    private String extractExtension(String lowerName) {
        int idx = lowerName.lastIndexOf('.');
        if (idx >= 0) {
            return lowerName.substring(idx);
        }
        return lowerName;
    }

    private boolean isKnownDangerousBinary(String lowerName) {
        return lowerName.endsWith(".exe") || lowerName.endsWith(".dll") || lowerName.endsWith(".so")
                || lowerName.endsWith(".bin") || lowerName.endsWith(".jar") || lowerName.endsWith(".class")
                || lowerName.endsWith(".war") || lowerName.endsWith(".dylib");
    }

    private void inspectFileEvidence(
            Path file,
            String fileName,
            String relativePath,
            Set<String> languages,
            Set<String> frameworks,
            List<String> projectFiles,
            Map<String, String> keySnippets
    ) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);

        if ("pom.xml".equals(lowerName)) {
            projectFiles.add("pom.xml");
            languages.add("Java");
            frameworks.add("Maven");
            readSnippetIfApplicable(file, "pom.xml", keySnippets);
            checkXmlForFrameworks(file, frameworks);
        } else if ("build.gradle".equals(lowerName) || "build.gradle.kts".equals(lowerName)) {
            projectFiles.add(fileName);
            languages.add("Java/Kotlin");
            frameworks.add("Gradle");
            readSnippetIfApplicable(file, fileName, keySnippets);
        } else if ("package.json".equals(lowerName)) {
            projectFiles.add("package.json");
            languages.add("JavaScript/TypeScript");
            frameworks.add("Node.js");
            readSnippetIfApplicable(file, "package.json", keySnippets);
        } else if ("requirements.txt".equals(lowerName) || "pyproject.toml".equals(lowerName)) {
            projectFiles.add(fileName);
            languages.add("Python");
            readSnippetIfApplicable(file, fileName, keySnippets);
        } else if (lowerName.endsWith(".java")) {
            languages.add("Java");
        } else if (lowerName.endsWith(".py")) {
            languages.add("Python");
        } else if (lowerName.endsWith(".ts") || lowerName.endsWith(".tsx")) {
            languages.add("TypeScript");
        } else if (lowerName.endsWith(".js") || lowerName.endsWith(".jsx")) {
            languages.add("JavaScript");
        } else if (lowerName.endsWith(".sql")) {
            frameworks.add("SQL");
        }
    }

    private void checkXmlForFrameworks(Path file, Set<String> frameworks) {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count++ < 100) {
                if (line.contains("spring-boot") || line.contains("org.springframework.boot")) {
                    frameworks.add("Spring Boot");
                    break;
                }
            }
        } catch (Exception ignored) {
            // Read-only bounded scan; ignore failure
        }
    }

    private void readSnippetIfApplicable(Path file, String snippetKey, Map<String, String> keySnippets) {
        if (keySnippets.size() >= 3 || keySnippets.containsKey(snippetKey)) {
            return;
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead < properties.getMaxSnippetLines()) {
                sb.append(line).append("\n");
                linesRead++;
            }
            keySnippets.put(snippetKey, sb.toString().trim());
        } catch (Exception ignored) {
            // Read-only bounded scan; ignore failure
        }
    }
}
