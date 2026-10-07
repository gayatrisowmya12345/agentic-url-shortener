package com.linkforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.FileChangeProposal;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.TestReportItem;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.implementation.GovernedBuildValidator;
import com.linkforge.service.implementation.GovernedExecutionProperties;
import com.linkforge.service.implementation.GovernedExecutionService;
import com.linkforge.service.implementation.GovernedPatchApplier;
import com.linkforge.service.implementation.ImplementationProposerAgent;
import com.linkforge.service.implementation.SafetyPolicyViolationException;
import com.linkforge.service.implementation.SurefireReportParser;
import com.linkforge.service.implementation.container.ContainerBuildExecutor;
import com.linkforge.service.implementation.container.DockerContainerBuildExecutor;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.inspection.CodebaseInspector;
import com.linkforge.service.release.ReleaseReadinessService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BrownfieldGovernedExecutionIntegrationTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private GovernedExecutionProperties executionProperties;

    @Autowired
    private CodebaseInspectionProperties inspectionProperties;

    @Autowired
    private ReleaseReadinessService releaseReadinessService;

    @Autowired
    private ImplementationProposerAgent proposerAgent;

    @Autowired
    private GovernedPatchApplier patchApplier;

    @Autowired
    private GovernedBuildValidator buildValidator;

    @Autowired
    private SurefireReportParser surefireReportParser;

    @TempDir
    Path tempDir;

    private Path approvedRoot;

    @BeforeEach
    void setUp() throws IOException {
        approvedRoot = tempDir.resolve("approved-root");
        Files.createDirectories(approvedRoot);
        inspectionProperties.setApprovedRoot(approvedRoot.toString());
        workflowRepository.clear();
    }

    private Map<String, String> computeDirectoryFingerprint(Path dir) throws IOException {
        Map<String, String> fingerprint = new ConcurrentHashMap<>();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        byte[] bytes = Files.readAllBytes(p);
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        fingerprint.put(dir.relativize(p).toString(), HexFormat.of().formatHex(md.digest(bytes)));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        return fingerprint;
    }

    @Test
    @DisplayName("Brownfield Safety: Untrusted wrapper is never executed on host, execution blocked safely when container isolation unavailable, leaving submitted repo untouched")
    void executionBlockedSafelyWhenIsolationUnavailable() throws IOException {
        ContainerBuildExecutor unavailableExecutor = new ContainerBuildExecutor() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public BuildValidationResult executeIsolatedBuild(Path disposableWorkspace, String buildCommand, ImplementationProposal proposal) {
                throw new IllegalStateException("Container isolation is unavailable on host.");
            }

            @Override
            public String getIsolationType() {
                return "DOCKER_CONTAINER";
            }
        };

        // Submitted repository with untrusted host trap wrapper
        Path repoPath = approvedRoot.resolve("untrusted-repo");
        Files.createDirectories(repoPath);
        Files.writeString(repoPath.resolve("pom.xml"), "<project></project>");

        Path trapMarker = tempDir.resolve("trap-executed.marker");
        Path untrustedWrapper = repoPath.resolve("mvnw");
        Files.writeString(untrustedWrapper, "#!/bin/sh\ntouch \"" + trapMarker.toAbsolutePath() + "\"\nexit 1\n");
        untrustedWrapper.toFile().setExecutable(true);

        Map<String, String> fingerprintBefore = computeDirectoryFingerprint(repoPath);

        GovernedExecutionService service = new GovernedExecutionService(
                proposerAgent, patchApplier, buildValidator, workflowRepository,
                inspectionProperties, executionProperties, null, null, unavailableExecutor, releaseReadinessService
        );

        WorkflowRun run = new WorkflowRun("Add custom alias validation", repoPath.toString());
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "approver", run.getCurrentPlanHash(), "Approve");
        run.setApproval(approval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        assertThatThrownBy(() -> service.executeImplementation(run.getId(), run.getCurrentPlanHash()))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("unconditionally blocked");

        // Verify submitted wrapper was NEVER executed on the host
        assertThat(Files.exists(trapMarker))
                .as("Trap marker must not exist; untrusted wrapper never executed on host")
                .isFalse();

        // Verify source repository is completely untouched
        Map<String, String> fingerprintAfter = computeDirectoryFingerprint(repoPath);
        assertThat(fingerprintAfter)
                .as("Submitted repository must remain 100% untouched when isolation is unavailable")
                .isEqualTo(fingerprintBefore);

        WorkflowRun persisted = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WorkflowStatus.BLOCKED);
        assertThat(persisted.getCurrentStage()).isEqualTo(WorkflowStage.BLOCKED);
        assertThat(persisted.getEvents()).anyMatch(e -> "MUTATION_BLOCKED".equals(e.eventType()));
    }

    @Test
    @DisplayName("Brownfield Execution: Real Docker container build executes generated changes and discovers actual Surefire reports")
    void realDockerContainerBrownfieldExecutionWithSurefireReportDiscovery() throws IOException {
        DockerContainerBuildExecutor dockerExecutor = new DockerContainerBuildExecutor(executionProperties, surefireReportParser);
        Assumptions.assumeTrue(
                dockerExecutor.isAvailable(),
                "Docker container runtime is unavailable on host; conditionally skipping real Docker-backed brownfield execution."
        );

        // Step 1: Create a brownfield repository fixture with custom package com.custom.shortener
        Path submittedRepo = approvedRoot.resolve("custom-shortener-repo");
        Path sourceDir = submittedRepo.resolve("src/main/java/com/custom/shortener");
        Files.createDirectories(sourceDir);

        String originalSource = """
                package com.custom.shortener;

                public final class AliasValidator {
                    public static void validate(String alias) {
                        // Original baseline validator
                    }
                }
                """;
        Path originalFile = sourceDir.resolve("AliasValidator.java");
        Files.writeString(originalFile, originalSource);

        String originalPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.custom</groupId>
                    <artifactId>custom-shortener</artifactId>
                    <version>1.0.0</version>
                </project>
                """;
        Files.writeString(submittedRepo.resolve("pom.xml"), originalPom);
        Files.writeString(submittedRepo.resolve("README.md"), "# Custom Shortener");

        // Capture byte-for-byte fingerprint before execution
        Map<String, String> fingerprintBefore = computeDirectoryFingerprint(submittedRepo);
        assertThat(fingerprintBefore).isNotEmpty();

        // Step 2: Codebase inspection extracts evidence from the submitted repository
        CodebaseInspector inspector = new CodebaseInspector(inspectionProperties);
        RepositoryEvidence evidence = inspector.inspect("custom-shortener-repo");

        assertThat(evidence.hasEvidence()).isTrue();
        assertThat(evidence.sampleSourcePaths()).anyMatch(p -> p.contains("com/custom/shortener/AliasValidator.java"));

        // Step 3: Governed Execution using the real DockerContainerBuildExecutor
        GovernedExecutionService service = new GovernedExecutionService(
                proposerAgent, patchApplier, buildValidator, workflowRepository,
                inspectionProperties, executionProperties, null, null, dockerExecutor, releaseReadinessService
        );

        WorkflowRun run = new WorkflowRun(
                "Build a URL shortener with custom alias validation bounds between 4 and 25 characters",
                submittedRepo.toString()
        );
        run.setAcceptanceCriteria(List.of("AC-1: Validate custom alias bounds between 4 and 25 characters"));
        run.setRepositoryEvidence(evidence);
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        service.proposeImplementation(run);

        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead-reviewer", run.getCurrentPlanHash(), "Approved");
        run.setApproval(approval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        GovernedExecutionRecord record = service.executeImplementation(run.getId(), run.getCurrentPlanHash());

        // Step 4: Verify execution outcomes
        assertThat(record.status()).isEqualTo("COMPLETED");
        assertThat(record.buildValidation().isSuccess()).isTrue();
        assertThat(record.buildValidation().testReports()).isNotEmpty();

        // Verify applied unified diff is captured and non-empty
        assertThat(record.appliedDiff()).isNotNull();
        assertThat(record.appliedDiff()).contains("AliasValidator.java");

        // Step 5: PROVE SUBMITTED REPOSITORY REMAINS 100% BYTE-FOR-BYTE UNCHANGED
        Map<String, String> fingerprintAfter = computeDirectoryFingerprint(submittedRepo);
        assertThat(fingerprintAfter)
                .as("Submitted repository must remain byte-for-byte unchanged after brownfield execution")
                .isEqualTo(fingerprintBefore);
    }

    @Test
    @DisplayName("Docker Container Command: Verifies exact security arguments, resource limits, no host credentials, no docker.sock, and no caller wrappers")
    void verifiesExactDockerCommandArgumentsAndSecurityHardening() {
        DockerContainerBuildExecutor executor = new DockerContainerBuildExecutor(executionProperties, surefireReportParser);
        Path mockWorkspace = tempDir.resolve("workspace-cmd-check");

        List<String> args = executor.buildDockerRunArguments(mockWorkspace, DockerContainerBuildExecutor.DEFAULT_PINNED_IMAGE);

        // Security options
        assertThat(args).contains("docker");
        assertThat(args).contains("run");
        assertThat(args).contains("--rm");
        assertThat(args).contains("--network");
        assertThat(args).contains("none");
        assertThat(args).contains("--memory");
        assertThat(args).contains("2048m");
        assertThat(args).contains("--cpus");
        assertThat(args).contains("2.0");
        assertThat(args).contains("--pids-limit");
        assertThat(args).contains("100");
        assertThat(args).contains("--security-opt");
        assertThat(args).contains("no-new-privileges");
        assertThat(args).contains("--cap-drop");
        assertThat(args).contains("ALL");

        // Workspace mount
        assertThat(args).anyMatch(a -> a.contains(mockWorkspace.getFileName().toString()) && a.endsWith(":/workspace:rw"));

        // Trusted binary, NOT caller wrapper or script
        assertThat(args).contains("mvn");
        assertThat(args).contains("--batch-mode");
        assertThat(args).contains("clean");
        assertThat(args).contains("verify");

        // Pinned image digest
        assertThat(args).anyMatch(a -> a.contains(DockerContainerBuildExecutor.DEFAULT_PINNED_IMAGE));

        // NO docker socket
        assertThat(args).noneMatch(a -> a.contains("docker.sock") || a.contains("docker.socket"));

        // NO caller wrapper or arbitrary script
        assertThat(args).noneMatch(a -> a.contains("mvnw") || a.contains(".sh"));

        // NO credentials or sensitive host paths
        assertThat(args).noneMatch(a -> a.contains("id_rsa") || a.contains(".ssh") || a.contains(".aws"));
    }

    @Test
    @DisplayName("Brownfield Validator: Regex contains derived numeric bounds and never literal %d placeholders")
    void brownfieldValidatorRegexContainsDerivedNumericBoundsAndNeverLiteralPlaceholders() throws IOException {
        Path submittedRepo = approvedRoot.resolve("regex-check-repo");
        Path sourceDir = submittedRepo.resolve("src/main/java/com/custom/shortener");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("AliasValidator.java"), "package com.custom.shortener; public final class AliasValidator {}");

        CodebaseInspector inspector = new CodebaseInspector(inspectionProperties);
        RepositoryEvidence evidence = inspector.inspect("regex-check-repo");

        ImplementationProposal proposal = proposerAgent.propose(
                "Build a URL shortener with custom alias validation bounds between 4 and 25 characters",
                List.of("AC-1: Validate custom alias bounds between 4 and 25 characters"),
                List.of(new PlannedTask("TASK-1", "Implement alias validation", "SECURITY", List.of(), "PENDING", "SECURITY")),
                evidence
        );

        assertThat(proposal.supported()).isTrue();
        FileChangeProposal validatorProposal = proposal.changes().stream()
                .filter(c -> c.path().contains("AliasValidator.java"))
                .findFirst().orElseThrow();

        // Must contain derived bounds
        assertThat(validatorProposal.proposedContent()).contains("MIN_LENGTH = 4;");
        assertThat(validatorProposal.proposedContent()).contains("MAX_LENGTH = 25;");
        assertThat(validatorProposal.proposedContent()).contains("Pattern.compile(\"^[a-zA-Z0-9_-]{4,25}$\")");

        // Must NEVER contain literal %d placeholders
        assertThat(validatorProposal.proposedContent()).doesNotContain("%d");
        assertThat(validatorProposal.proposedContent()).doesNotContain("%%d");
    }

    @Test
    @DisplayName("Brownfield Runtime Path: Rejects proposal when relevant runtime path cannot be safely identified")
    void rejectsBrownfieldProposalWhenRuntimePathCannotBeIdentified() throws IOException {
        Path unrelatedRepo = approvedRoot.resolve("unrelated-repo");
        Path sourceDir = unrelatedRepo.resolve("src/main/java/com/other/billing");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("InvoiceService.java"), "package com.other.billing; public class InvoiceService {}");
        Files.writeString(unrelatedRepo.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        CodebaseInspector inspector = new CodebaseInspector(inspectionProperties);
        RepositoryEvidence evidence = inspector.inspect("unrelated-repo");

        ImplementationProposal proposal = proposerAgent.propose(
                "Build a URL shortener with custom alias validation bounds between 4 and 25 characters",
                List.of("AC-1: Validate custom alias bounds between 4 and 25 characters"),
                List.of(new PlannedTask("TASK-1", "Implement alias validation", "SECURITY", List.of(), "PENDING", "SECURITY")),
                evidence
        );

        assertThat(proposal.supported()).isFalse();
        assertThat(proposal.unsupportedReason())
                .contains("could not be safely identified from inspected source evidence");
    }
}
