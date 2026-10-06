package com.linkforge.service.inspection;

import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.exception.InvalidRepositoryPathException;
import com.linkforge.domain.workflow.scenario.exception.PathTraversalException;
import com.linkforge.domain.workflow.scenario.exception.RepositoryLimitExceededException;
import com.linkforge.domain.workflow.scenario.exception.SymlinkEscapeException;
import com.linkforge.domain.workflow.scenario.exception.UnsupportedFileTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodebaseInspectorTest {

    @TempDir
    Path tempDir;

    private Path approvedRoot;
    private CodebaseInspectionProperties properties;
    private CodebaseInspector inspector;

    @BeforeEach
    void setUp() throws IOException {
        approvedRoot = tempDir.resolve("approved-root");
        Files.createDirectories(approvedRoot);
        properties = new CodebaseInspectionProperties();
        properties.setApprovedRoot(approvedRoot.toString());
        inspector = new CodebaseInspector(properties);
    }

    @Test
    @DisplayName("Successfully inspects safe repository fixture using relative path under approved root")
    void inspectValidRepositoryWithRelativePath() throws IOException {
        Path repo = approvedRoot.resolve("valid-repo");
        Files.createDirectories(repo.resolve("src/main/java/com/example"));

        String pomContent = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo-service</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;
        Files.writeString(repo.resolve("pom.xml"), pomContent);

        String javaCode = """
                package com.example;
                public class DemoApplication {
                    public static void main(String[] args) {}
                }
                """;
        Files.writeString(repo.resolve("src/main/java/com/example/DemoApplication.java"), javaCode);
        Files.writeString(repo.resolve("README.md"), "# Demo Application");

        // Use relative path relative to approved root
        RepositoryEvidence evidence = inspector.inspect("valid-repo");

        assertThat(evidence).isNotNull();
        assertThat(evidence.hasEvidence()).isTrue();
        assertThat(evidence.totalFiles()).isEqualTo(3);
        assertThat(evidence.detectedLanguages()).contains("Java");
        assertThat(evidence.detectedFrameworks()).contains("Maven", "Spring Boot");
        assertThat(evidence.projectFileNames()).contains("pom.xml");
        assertThat(evidence.sampleSourcePaths()).anyMatch(p -> p.endsWith("DemoApplication.java"));
        assertThat(evidence.keySnippets()).containsKey("pom.xml");
        assertThat(evidence.summary()).contains("valid-repo");
    }

    @Test
    @DisplayName("Rejects absolute repository paths")
    void rejectAbsolutePath() throws IOException {
        Path repo = approvedRoot.resolve("valid-repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("pom.xml"), "<project></project>");

        // Passing absolute path must be rejected
        assertThatThrownBy(() -> inspector.inspect(repo.toAbsolutePath().toString()))
                .isInstanceOf(InvalidRepositoryPathException.class)
                .hasMessageContaining("Absolute repository paths are rejected");
    }

    @Test
    @DisplayName("Rejects null or blank repository path")
    void rejectNullOrBlankPath() {
        assertThatThrownBy(() -> inspector.inspect(null))
                .isInstanceOf(InvalidRepositoryPathException.class)
                .hasMessageContaining("cannot be null or blank");

        assertThatThrownBy(() -> inspector.inspect("   "))
                .isInstanceOf(InvalidRepositoryPathException.class)
                .hasMessageContaining("cannot be null or blank");
    }

    @Test
    @DisplayName("Rejects non-existent repository path")
    void rejectNonExistentPath() {
        assertThatThrownBy(() -> inspector.inspect("missing-repo-dir"))
                .isInstanceOf(InvalidRepositoryPathException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("Rejects file path when a directory is expected")
    void rejectFileInsteadOfDirectory() throws IOException {
        Path filePath = approvedRoot.resolve("somefile.txt");
        Files.writeString(filePath, "sample content");

        assertThatThrownBy(() -> inspector.inspect("somefile.txt"))
                .isInstanceOf(InvalidRepositoryPathException.class)
                .hasMessageContaining("is not a directory");
    }

    @Test
    @DisplayName("Rejects path traversal escaping approved root")
    void rejectPathTraversalEscape() throws IOException {
        Path outsideSibling = tempDir.resolve("outside-sibling");
        Files.createDirectories(outsideSibling);

        assertThatThrownBy(() -> inspector.inspect("../outside-sibling"))
                .isInstanceOf(PathTraversalException.class)
                .hasMessageContaining("Directory traversal sequence detected");

        assertThatThrownBy(() -> inspector.inspect("sub/../../outside-sibling"))
                .isInstanceOf(PathTraversalException.class)
                .hasMessageContaining("Directory traversal sequence detected");
    }

    @Test
    @DisplayName("Rejects symbolic link escaping selected repository to another location inside approved root")
    void rejectSymlinkEscapingSelectedRepositoryInsideApprovedRoot() throws IOException {
        Path repo = approvedRoot.resolve("selected-repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("valid.txt"), "hello");

        // Create sibling directory inside approvedRoot (not outside approved root, but outside selected-repo)
        Path siblingInsideApprovedRoot = approvedRoot.resolve("sibling-internal-dir");
        Files.createDirectories(siblingInsideApprovedRoot);
        Path targetFile = siblingInsideApprovedRoot.resolve("other-secret.txt");
        Files.writeString(targetFile, "internal secret");

        Path symlink = repo.resolve("link-to-internal.txt");
        try {
            Files.createSymbolicLink(symlink, targetFile);
        } catch (UnsupportedOperationException e) {
            // Environment does not support symlinks; skip
            return;
        }

        assertThatThrownBy(() -> inspector.inspect("selected-repo"))
                .isInstanceOf(SymlinkEscapeException.class)
                .hasMessageContaining("escapes selected repository boundary");
    }

    @Test
    @DisplayName("Rejects symbolic link escaping selected repository to outside approved root")
    void rejectSymlinkEscapingApprovedRoot() throws IOException {
        Path repo = approvedRoot.resolve("symlink-outside-repo");
        Files.createDirectories(repo);

        // Create sibling directory under tempDir that is outside approvedRoot
        Path outsideSibling = tempDir.resolve("outside-sibling-fixture");
        Files.createDirectories(outsideSibling);
        Path outsideFile = outsideSibling.resolve("outside.txt");
        Files.writeString(outsideFile, "outside content");

        Path symlink = repo.resolve("link-outside.txt");
        try {
            Files.createSymbolicLink(symlink, outsideFile);
        } catch (UnsupportedOperationException e) {
            return;
        }

        assertThatThrownBy(() -> inspector.inspect("symlink-outside-repo"))
                .isInstanceOf(SymlinkEscapeException.class)
                .hasMessageContaining("escapes selected repository boundary");
    }

    @Test
    @DisplayName("Rejects repository containing unsupported binary file before reading content")
    void rejectUnsupportedFileType() throws IOException {
        Path repo = approvedRoot.resolve("binary-repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("Main.java"), "public class Main {}");
        Files.write(repo.resolve("malicious.exe"), new byte[]{0x4D, 0x5A, 0x00});

        assertThatThrownBy(() -> inspector.inspect("binary-repo"))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("malicious.exe");
    }

    @Test
    @DisplayName("Rejects repository containing dangerous script files (.sh, .bat)")
    void rejectDangerousScriptFiles() throws IOException {
        Path repo = approvedRoot.resolve("script-repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("service.py"), "print('hello')");
        Files.writeString(repo.resolve("deploy.sh"), "#!/bin/bash\nrm -rf /");

        assertThatThrownBy(() -> inspector.inspect("script-repo"))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("deploy.sh");
    }

    @Test
    @DisplayName("Rejects repository exceeding configured file count limit")
    void rejectFileCountLimitExceeded() throws IOException {
        properties.setMaxFileCount(3);
        Path repo = approvedRoot.resolve("limit-repo");
        Files.createDirectories(repo);

        for (int i = 1; i <= 4; i++) {
            Files.writeString(repo.resolve("file" + i + ".txt"), "content " + i);
        }

        assertThatThrownBy(() -> inspector.inspect("limit-repo"))
                .isInstanceOf(RepositoryLimitExceededException.class)
                .hasMessageContaining("file count limit exceeded");
    }

    @Test
    @DisplayName("Rejects repository exceeding total byte size limit")
    void rejectTotalSizeLimitExceeded() throws IOException {
        properties.setMaxTotalSizeBytes(500L);
        Path repo = approvedRoot.resolve("size-repo");
        Files.createDirectories(repo);

        Files.writeString(repo.resolve("large.txt"), "A".repeat(600));

        assertThatThrownBy(() -> inspector.inspect("size-repo"))
                .isInstanceOf(RepositoryLimitExceededException.class)
                .hasMessageContaining("size limit exceeded");
    }

    @Test
    @DisplayName("Rejects repository exceeding individual file size limit")
    void rejectIndividualFileSizeLimitExceeded() throws IOException {
        properties.setMaxFileSizeBytes(200L);
        Path repo = approvedRoot.resolve("single-file-size-repo");
        Files.createDirectories(repo);

        Files.writeString(repo.resolve("file-exceeds.txt"), "X".repeat(250));

        assertThatThrownBy(() -> inspector.inspect("single-file-size-repo"))
                .isInstanceOf(RepositoryLimitExceededException.class)
                .hasMessageContaining("File size limit exceeded");
    }

    @Test
    @DisplayName("Honors max snippet lines and max evidence files configuration")
    void honorsSnippetAndEvidenceFileLimits() throws IOException {
        properties.setMaxSnippetLines(5);
        properties.setMaxEvidenceFiles(2);

        Path repo = approvedRoot.resolve("snippet-limits-repo");
        Files.createDirectories(repo);

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("<line>").append(i).append("</line>\n");
        }
        Files.writeString(repo.resolve("pom.xml"), sb.toString());
        Files.writeString(repo.resolve("FileA.java"), "class FileA {}");
        Files.writeString(repo.resolve("FileB.java"), "class FileB {}");
        Files.writeString(repo.resolve("FileC.java"), "class FileC {}");

        RepositoryEvidence evidence = inspector.inspect("snippet-limits-repo");

        // Snippet lines capped at 5
        String snippet = evidence.keySnippets().get("pom.xml");
        assertThat(snippet).isNotNull();
        String[] lines = snippet.split("\n");
        assertThat(lines.length).isLessThanOrEqualTo(5);

        // Sample paths capped at 2
        assertThat(evidence.sampleSourcePaths()).hasSize(2);
    }
}
