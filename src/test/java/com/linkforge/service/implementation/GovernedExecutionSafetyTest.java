package com.linkforge.service.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.ai.provider.LlmModelProvider;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.FileChangeOperation;
import com.linkforge.domain.workflow.implementation.FileChangeProposal;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.RollbackResult;
import com.linkforge.domain.link.exception.InvalidAliasException;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.link.AliasValidator;
import com.linkforge.service.security.InvalidPlanHashException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernedExecutionSafetyTest {

    @TempDir
    Path tempDir;

    private GovernedExecutionProperties executionProperties;
    private GovernedPatchApplier patchApplier;
    private GovernedBuildValidator buildValidator;
    private ImplementationProposerAgent proposerAgent;
    private WorkflowRepository workflowRepository;
    private GovernedExecutionService executionService;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        workflowRepository = new WorkflowRepository(jdbcTemplate, new ObjectMapper());

        executionProperties = new GovernedExecutionProperties();
        patchApplier = new GovernedPatchApplier(executionProperties);
        buildValidator = new GovernedBuildValidator(executionProperties);
        proposerAgent = new ImplementationProposerAgent(null, new ObjectMapper());

        CodebaseInspectionProperties inspectionProperties = new CodebaseInspectionProperties();
        inspectionProperties.setApprovedRoot(tempDir.toString());

        executionService = new GovernedExecutionService(
                proposerAgent,
                patchApplier,
                buildValidator,
                workflowRepository,
                inspectionProperties,
                executionProperties
        );
    }

    @Test
    @DisplayName("Safety Boundary 1: Human approval is strictly required before source mutation")
    void approvalRequiredBeforeMutation() {
        WorkflowRun run = new WorkflowRun("Create a URL shortener service with custom alias validation");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        executionService.proposeImplementation(run);
        workflowRepository.save(run);

        assertThatThrownBy(() -> executionService.executeImplementation(run.getId(), run.getCurrentPlanHash()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Human approval is required");

        WorkflowRun persisted = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WorkflowStatus.BLOCKED);
        assertThat(persisted.getCurrentStage()).isEqualTo(WorkflowStage.BLOCKED);
        assertThat(persisted.getEvents()).anyMatch(e -> "MUTATION_BLOCKED".equals(e.eventType()));
    }

    @Test
    @DisplayName("Safety Boundary 2: Approval plan hash mismatch blocks mutation safely")
    void approvalHashMismatch() {
        WorkflowRun run = new WorkflowRun("Create a URL shortener service with custom alias validation");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        executionService.proposeImplementation(run);

        // Approve with the current plan hash
        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "approver", run.getCurrentPlanHash(), "Approved");
        run.setApproval(approval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        // Attempt execution with mismatched plan hash
        assertThatThrownBy(() -> executionService.executeImplementation(run.getId(), "mismatched-plan-hash-12345"))
                .isInstanceOf(InvalidPlanHashException.class);

        WorkflowRun persisted = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WorkflowStatus.BLOCKED);
        assertThat(persisted.getEvents()).anyMatch(e -> "MUTATION_BLOCKED".equals(e.eventType()));
    }

    @Test
    @DisplayName("Safety Boundary 3: Rejected plan blocks execution attempt")
    void rejectedPlanBlocksExecution() {
        WorkflowRun run = new WorkflowRun("Create a URL shortener with custom alias validation");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        executionService.proposeImplementation(run);

        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "REJECTED", "approver", run.getCurrentPlanHash(), "Plan rejected");
        run.setApproval(approval);
        run.transitionTo(WorkflowStatus.REJECTED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        assertThatThrownBy(() -> executionService.executeImplementation(run.getId(), run.getCurrentPlanHash()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected");

        WorkflowRun persisted = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WorkflowStatus.BLOCKED);
    }

    @Test
    @DisplayName("Safety Boundary 4: Deterministic offline proposal succeeds without model dependencies")
    void deterministicOfflineProposal() {
        ImplementationProposal proposal = proposerAgent.propose(
                "Create a URL shortener with custom alias validation",
                List.of("Validate alias alphanumeric characters"),
                List.of(new PlannedTask("TASK-1", "Alias Validator", "Validate alias", List.of(), "PENDING", "API_BEHAVIOR")),
                null
        );

        assertThat(proposal.supported()).isTrue();
        assertThat(proposal.scope()).isEqualTo("ALIAS_VALIDATION");
        assertThat(proposal.changes()).isNotEmpty();

        FileChangeProposal change = proposal.changes().get(0);
        assertThat(change.path()).endsWith("AliasValidator.java");
        assertThat(change.operation()).isEqualTo(FileChangeOperation.MODIFY);
        assertThat(change.expectedInputHash()).isNotBlank();
        assertThat(change.proposedContent()).contains("class AliasValidator");
        assertThat(change.targetHash()).isNotBlank();
        assertThat(change.taskLineage()).isEqualTo("TASK-1");

        FileChangeProposal testChange = proposal.changes().get(1);
        assertThat(testChange.path()).endsWith("CustomAliasValidationTest.java");
        assertThat(testChange.operation()).isEqualTo(FileChangeOperation.CREATE);
        assertThat(testChange.proposedContent()).contains("class CustomAliasValidationTest");
    }

    @Test
    @DisplayName("Safety Boundary 5: Model provider returning malformed JSON falls back gracefully")
    void invalidModelOutputFallback() {
        LlmModelProvider mockProvider = mock(LlmModelProvider.class);
        when(mockProvider.isEnabled()).thenReturn(true);
        when(mockProvider.generate(anyString(), anyString())).thenReturn("This is not valid JSON at all ```json bad syntax");

        ImplementationProposerAgent agentWithMock = new ImplementationProposerAgent(mockProvider, new ObjectMapper());

        ImplementationProposal proposal = agentWithMock.propose(
                "Create a URL shortener with custom alias validation",
                List.of("Validate alias alphanumeric characters"),
                List.of(),
                null
        );

        assertThat(proposal).isNotNull();
        assertThat(proposal.supported()).isTrue();
        assertThat(proposal.changes()).isNotEmpty();
        assertThat(proposal.changes().get(0).path()).contains("AliasValidator.java");
    }

    @Test
    @DisplayName("Safety Boundary 6: Model provider proposing forbidden command execution is safely discarded")
    void modelProposingForbiddenCommandsDiscarded() {
        LlmModelProvider mockProvider = mock(LlmModelProvider.class);
        when(mockProvider.isEnabled()).thenReturn(true);
        when(mockProvider.generate(anyString(), anyString())).thenReturn("""
                {
                  "path": "src/main/java/com/linkforge/service/link/GeneratedHelper.java",
                  "operation": "CREATE",
                  "proposedContent": "package com.linkforge.service.link; class Malicious { void run() { Runtime.getRuntime().exec(\\"rm -rf /\\"); } }",
                  "description": "Exploit attempt"
                }
                """);

        ImplementationProposerAgent agentWithMock = new ImplementationProposerAgent(mockProvider, new ObjectMapper());

        ImplementationProposal proposal = agentWithMock.propose(
                "Create a URL shortener with custom alias validation",
                List.of("Validate alias"),
                List.of(),
                null
        );

        // Fallback occurred, malicious content discarded
        assertThat(proposal.supported()).isTrue();
        assertThat(proposal.changes().get(0).proposedContent()).doesNotContain("Runtime.getRuntime()");
    }

    @Test
    @DisplayName("Safety Boundary 7: Directory traversal and absolute paths are strictly rejected")
    void unsafeTraversalPathRejected() {
        Path workspace = tempDir.resolve("workspace-traversal");
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {}

        FileChangeProposal traversalChange = FileChangeProposal.of(
                "../outside.java",
                FileChangeOperation.CREATE,
                "public class Outside {}",
                null, "TASK-1", "AC-1", "SECURITY", "Escape test"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(traversalChange)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("traversal");

        FileChangeProposal absoluteChange = FileChangeProposal.of(
                "/tmp/escape.java",
                FileChangeOperation.CREATE,
                "public class Escape {}",
                null, "TASK-1", "AC-1", "SECURITY", "Escape test"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(absoluteChange)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("Absolute paths are not allowed");
    }

    @Test
    @DisplayName("Safety Boundary 8: Disallowed file extensions are strictly rejected")
    void disallowedFileTypeRejected() {
        Path workspace = tempDir.resolve("workspace-ext");
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {}

        FileChangeProposal shellScriptChange = FileChangeProposal.of(
                "src/main/resources/attack.sh",
                FileChangeOperation.CREATE,
                "#!/bin/bash\necho bad",
                null, "TASK-1", "AC-1", "SECURITY", "Script test"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(shellScriptChange)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("Disallowed file extension");
    }

    @Test
    @DisplayName("Safety Boundary 9: Operation count exceeding configured limit is rejected")
    void operationCountLimitEnforced() {
        Path workspace = tempDir.resolve("workspace-ops");
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {}

        List<FileChangeProposal> tooManyChanges = new ArrayList<>();
        for (int i = 0; i <= executionProperties.getMaxOperations(); i++) {
            tooManyChanges.add(FileChangeProposal.of(
                    "src/main/java/com/linkforge/service/link/File" + i + ".java",
                    FileChangeOperation.CREATE,
                    "public class File" + i + " {}",
                    null, "TASK-1", "AC-1", "API", "Test " + i
            ));
        }

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, tooManyChanges))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("operation count");
    }

    @Test
    @DisplayName("Safety Boundary 10: File and total byte size limits are strictly enforced")
    void byteSizeLimitsEnforced() {
        Path workspace = tempDir.resolve("workspace-bytes");
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {}

        String hugeContent = "A".repeat((int) executionProperties.getMaxFileSizeBytes() + 1024);
        FileChangeProposal oversizeChange = FileChangeProposal.of(
                "src/main/java/com/linkforge/service/link/Huge.java",
                FileChangeOperation.CREATE,
                hugeContent,
                null, "TASK-1", "AC-1", "API", "Oversize test"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(oversizeChange)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("exceeds max file size limit");
    }

    @Test
    @DisplayName("Safety Boundary 11: Duplicate paths in proposal are rejected")
    void duplicatePathsRejected() {
        Path workspace = tempDir.resolve("workspace-dupes");
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {}

        FileChangeProposal c1 = FileChangeProposal.of(
                "src/main/java/com/linkforge/service/link/Duplicate.java",
                FileChangeOperation.CREATE,
                "public class Duplicate {}",
                null, "TASK-1", "AC-1", "API", "First"
        );

        FileChangeProposal c2 = FileChangeProposal.of(
                "src/main/java/com/linkforge/service/link/Duplicate.java",
                FileChangeOperation.MODIFY,
                "public class Duplicate { int x; }",
                null, "TASK-1", "AC-1", "API", "Second"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(c1, c2)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("duplicate change for path");
    }

    @Test
    @DisplayName("Safety Boundary 12: Stale optimistic input hash check rejects modification")
    void staleInputHashRejected() throws IOException {
        Path workspace = tempDir.resolve("workspace-stale");
        Files.createDirectories(workspace.resolve("src/main/java/com/linkforge/service/link"));
        Path targetFile = workspace.resolve("src/main/java/com/linkforge/service/link/Existing.java");
        Files.writeString(targetFile, "public class Existing { // version 1 }");

        FileChangeProposal staleChange = FileChangeProposal.of(
                "src/main/java/com/linkforge/service/link/Existing.java",
                FileChangeOperation.MODIFY,
                "public class Existing { // version 2 }",
                "0000000000000000000000000000000000000000000000000000000000000000", // Stale/wrong hash
                "TASK-1", "AC-1", "API", "Modify existing"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(staleChange)))
                .isInstanceOf(StaleInputHashException.class)
                .hasMessageContaining("Optimistic hash check failed");
    }

    @Test
    @DisplayName("Safety Boundary 13: Atomic application and verified rollback restore original file state")
    void rollbackRestoresOriginalFiles() throws IOException {
        Path workspace = tempDir.resolve("workspace-rollback");
        Files.createDirectories(workspace.resolve("src/main/java/com/linkforge/service/link"));
        Path targetFile = workspace.resolve("src/main/java/com/linkforge/service/link/Target.java");
        String originalContent = "public class Target { public static final String V = \"ORIGINAL\"; }";
        Files.writeString(targetFile, originalContent);
        String expectedHash = GovernedPatchApplier.computeSha256(originalContent.getBytes(StandardCharsets.UTF_8));

        FileChangeProposal validModify = FileChangeProposal.of(
                "src/main/java/com/linkforge/service/link/Target.java",
                FileChangeOperation.MODIFY,
                "public class Target { public static final String V = \"MUTATED\"; }",
                expectedHash,
                "TASK-1", "AC-1", "API", "Mutation"
        );

        GovernedPatchApplier.ApplicationResult appResult = patchApplier.applyChanges(workspace, List.of(validModify));
        assertThat(Files.readString(targetFile)).contains("MUTATED");

        // Trigger rollback
        RollbackResult rollbackResult = patchApplier.rollback(appResult.originalSnapshots(), "Verification failure simulated");
        assertThat(rollbackResult.success()).isTrue();
        assertThat(Files.readString(targetFile)).isEqualTo(originalContent);
        assertThat(rollbackResult.restoredHashes().values()).contains(expectedHash);
    }

    @Test
    @DisplayName("Safety Boundary 14: Out-of-scope requirement safely rejects execution")
    void unsupportedRequirementBehavior() {
        WorkflowRun run = new WorkflowRun("Build a real-time quantum chess game with multiplayer websocket lobby");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        ImplementationProposal proposal = executionService.proposeImplementation(run);

        assertThat(proposal.supported()).isFalse();
        assertThat(proposal.unsupportedReason()).contains("outside the supported URL-shortener implementation scope");

        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "approver", run.getCurrentPlanHash(), "Approved");
        run.setApproval(approval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        assertThatThrownBy(() -> executionService.executeImplementation(run.getId(), run.getCurrentPlanHash()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the supported URL-shortener implementation scope");

        WorkflowRun persisted = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WorkflowStatus.BLOCKED);
        assertThat(persisted.getEvents()).anyMatch(e -> "MUTATION_BLOCKED".equals(e.eventType()));
    }

    @Test
    @DisplayName("Safety Boundary 15: Build validation failure triggers verified rollback and transitions workflow to ROLLED_BACK")
    void buildValidationFailureTriggersVerifiedRollback() {
        GovernedBuildValidator failingValidator = mock(GovernedBuildValidator.class);
        when(failingValidator.validateBuild(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BuildValidationResult(
                        "./mvnw --batch-mode test -Dtest=CustomAliasValidationTest", 1, 450, "Compilation error: syntax error", "BUILD_FAILED", java.time.Instant.now()
                ));

        GovernedExecutionService serviceWithFailingBuild = new GovernedExecutionService(
                proposerAgent,
                patchApplier,
                failingValidator,
                workflowRepository,
                new CodebaseInspectionProperties(),
                executionProperties
        );

        WorkflowRun run = new WorkflowRun("Create a URL shortener with custom alias validation");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        serviceWithFailingBuild.proposeImplementation(run);

        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead-dev", run.getCurrentPlanHash(), "Approved");
        run.setApproval(approval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        GovernedExecutionRecord record = serviceWithFailingBuild.executeImplementation(run.getId(), run.getCurrentPlanHash());

        assertThat(record.status()).isEqualTo("ROLLED_BACK");
        assertThat(record.stage()).isEqualTo("ROLLED_BACK");
        assertThat(record.rollback()).isNotNull();
        assertThat(record.rollback().success()).isTrue();

        WorkflowRun persisted = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WorkflowStatus.ROLLED_BACK);
        assertThat(persisted.getCurrentStage()).isEqualTo(WorkflowStage.ROLLED_BACK);
        assertThat(persisted.getEvents()).anyMatch(e -> "VALIDATION_FAILED".equals(e.eventType()));
        assertThat(persisted.getEvents()).anyMatch(e -> "ROLLBACK_VERIFIED".equals(e.eventType()));
        assertThat(persisted.getEvents()).anyMatch(e -> "WORKFLOW_ROLLED_BACK".equals(e.eventType()));
    }

    @Test
    @DisplayName("Safety Boundary 16: Deliberately hanging build child process times out and terminates descendants")
    void buildTimeoutKillsHangingProcessAndDescendants() throws IOException {
        Path hangingWorkspace = tempDir.resolve("workspace-hanging");
        Files.createDirectories(hangingWorkspace);
        Path dummyPom = hangingWorkspace.resolve("pom.xml");
        Files.writeString(dummyPom, "<project></project>");

        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path mvnwScript = hangingWorkspace.resolve(isWin ? "mvnw.cmd" : "mvnw");
        if (isWin) {
            Files.writeString(mvnwScript, "@echo off\r\nping 127.0.0.1 -n 30 > nul\r\n");
        } else {
            Files.writeString(mvnwScript, "#!/bin/sh\nsleep 25\n");
            mvnwScript.toFile().setExecutable(true, false);
        }

        GovernedExecutionProperties shortTimeoutProps = new GovernedExecutionProperties();
        shortTimeoutProps.setBuildTimeoutSeconds(1);
        shortTimeoutProps.setFixedBuildCommand("./mvnw test");

        GovernedBuildValidator hangingValidator = new GovernedBuildValidator(shortTimeoutProps);
        BuildValidationResult result = hangingValidator.validateBuild(hangingWorkspace);

        assertThat(result.status()).isEqualTo("TIMED_OUT");
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.output()).contains("timed out");
    }

    @Test
    @DisplayName("Safety Boundary 17: Process output cap is strictly enforced even for a single massive line without newlines")
    void outputCapIsNeverExceededEvenOnHugeSingleLine() throws IOException {
        Path capWorkspace = tempDir.resolve("workspace-cap");
        Files.createDirectories(capWorkspace);
        Path dummyPom = capWorkspace.resolve("pom.xml");
        Files.writeString(dummyPom, "<project></project>");

        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path mvnwScript = capWorkspace.resolve(isWin ? "mvnw.cmd" : "mvnw");
        if (isWin) {
            Files.writeString(mvnwScript, "@echo off\r\nfor /L %%i in (1,1,1000) do <nul set /p=A\r\n");
        } else {
            // Emits 20,000 'A' characters on a single continuous line without newlines
            Files.writeString(mvnwScript, "#!/bin/sh\nprintf '%*s' 20000 | tr ' ' 'A'\n");
            mvnwScript.toFile().setExecutable(true, false);
        }

        GovernedExecutionProperties capProps = new GovernedExecutionProperties();
        capProps.setMaxCapturedOutputChars(100);
        capProps.setBuildTimeoutSeconds(10);
        capProps.setFixedBuildCommand("./mvnw test");

        GovernedBuildValidator capValidator = new GovernedBuildValidator(capProps);
        BuildValidationResult result = capValidator.validateBuild(capWorkspace);

        assertThat(result.output().length()).isLessThanOrEqualTo(100 + 75);
        assertThat(result.output()).contains("[output truncated after 100 characters]");
    }

    @Test
    @DisplayName("Safety Boundary 18: Untrusted submitted brownfield repository wrapper is never executed on host")
    void untrustedSubmittedRepositoryWrapperNeverExecuted() throws IOException {
        Path untrustedRepo = tempDir.resolve("untrusted-caller-repo");
        Files.createDirectories(untrustedRepo);
        Path sentinelFile = tempDir.resolve("malicious_sentinel.txt");

        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path fakeMvnw = untrustedRepo.resolve(isWin ? "mvnw.cmd" : "mvnw");
        if (isWin) {
            Files.writeString(fakeMvnw, "@echo off\r\necho EXECUTED > \"" + sentinelFile.toAbsolutePath() + "\"\r\n");
        } else {
            Files.writeString(fakeMvnw, "#!/bin/sh\necho 'EXECUTED' > \"" + sentinelFile.toAbsolutePath() + "\"\n");
            fakeMvnw.toFile().setExecutable(true, false);
        }

        WorkflowRun run = new WorkflowRun("Add custom alias validation for links");
        run.setRepositoryPath("untrusted-caller-repo");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        executionService.proposeImplementation(run);

        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead-dev", run.getCurrentPlanHash(), "Approved");
        run.setApproval(approval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        // Attempt execution: must be rejected before workspace copy or build execution
        assertThatThrownBy(() -> executionService.executeImplementation(run.getId(), run.getCurrentPlanHash()))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("disallowed to prevent executing untrusted wrappers");

        // The malicious wrapper script MUST NEVER have executed
        assertThat(Files.exists(sentinelFile)).isFalse();

        WorkflowRun persisted = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WorkflowStatus.BLOCKED);
        assertThat(persisted.getCurrentStage()).isEqualTo(WorkflowStage.BLOCKED);
        assertThat(persisted.getEvents()).anyMatch(e -> "MUTATION_BLOCKED".equals(e.eventType()));
    }

    @Test
    @DisplayName("Safety Boundary 19: Operation CREATE cannot overwrite an existing file")
    void createOperationCannotOverwriteExistingFile() throws IOException {
        Path workspace = tempDir.resolve("ws-create-existing");
        Files.createDirectories(workspace.resolve("src"));
        Path existing = workspace.resolve("src/Existing.java");
        Files.writeString(existing, "public class Existing {}");

        FileChangeProposal createExisting = FileChangeProposal.of(
                "src/Existing.java",
                FileChangeOperation.CREATE,
                "public class Mutated {}",
                null,
                "TASK-1", "AC-1", "API", "Overwrite attempt"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(createExisting)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("Cannot CREATE file that already exists");
    }

    @Test
    @DisplayName("Safety Boundary 20: Operation MODIFY requires a non-blank expectedInputHash")
    void modifyOperationRequiresNonblankExpectedInputHash() throws IOException {
        Path workspace = tempDir.resolve("ws-modify-hash");
        Files.createDirectories(workspace.resolve("src"));
        Path existing = workspace.resolve("src/Existing.java");
        Files.writeString(existing, "public class Existing {}");

        FileChangeProposal modifyNullHash = FileChangeProposal.of(
                "src/Existing.java",
                FileChangeOperation.MODIFY,
                "public class Mutated {}",
                null,
                "TASK-1", "AC-1", "API", "Null hash"
        );
        FileChangeProposal modifyBlankHash = FileChangeProposal.of(
                "src/Existing.java",
                FileChangeOperation.MODIFY,
                "public class Mutated {}",
                "   ",
                "TASK-1", "AC-1", "API", "Blank hash"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(modifyNullHash)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("Operation MODIFY requires a non-blank expectedInputHash");

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(modifyBlankHash)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("Operation MODIFY requires a non-blank expectedInputHash");
    }

    @Test
    @DisplayName("Safety Boundary 21: Operation MODIFY targeting non-existent file is rejected")
    void modifyOperationOnNonexistentFileRejected() {
        Path workspace = tempDir.resolve("ws-modify-missing");
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {}

        FileChangeProposal modifyMissing = FileChangeProposal.of(
                "src/DoesNotExist.java",
                FileChangeOperation.MODIFY,
                "public class Mutated {}",
                "some-hash",
                "TASK-1", "AC-1", "API", "Missing file"
        );

        assertThatThrownBy(() -> patchApplier.applyChanges(workspace, List.of(modifyMissing)))
                .isInstanceOf(SafetyPolicyViolationException.class)
                .hasMessageContaining("Cannot MODIFY file that does not exist");
    }

    @Test
    @DisplayName("Safety Boundary 22: Model responses violating operation and path policies are safely rejected")
    void modelResponsePolicyViolationsSafelyRejected() {
        LlmModelProvider mockLlm = mock(LlmModelProvider.class);
        ImplementationProposerAgent agentWithMock = new ImplementationProposerAgent(mockLlm, new ObjectMapper());

        // 1. Invalid operation
        when(mockLlm.generate(anyString(), anyString())).thenReturn("""
                {
                  "path": "src/main/java/com/linkforge/service/link/Test.java",
                  "operation": "EXPLODE",
                  "proposedContent": "public class Test {}"
                }
                """);
        ImplementationProposal prop1 = agentWithMock.propose("Add custom alias validation", List.of(), List.of(), null);
        // Must reject model proposal and fall back to safe deterministic proposal
        assertThat(prop1.supported()).isTrue();
        assertThat(prop1.changes()).noneMatch(c -> "EXPLODE".equals(c.operation().name()));

        // 2. Path traversal
        when(mockLlm.generate(anyString(), anyString())).thenReturn("""
                {
                  "path": "../../Escape.java",
                  "operation": "CREATE",
                  "proposedContent": "public class Escape {}"
                }
                """);
        ImplementationProposal prop2 = agentWithMock.propose("Add custom alias validation", List.of(), List.of(), null);
        assertThat(prop2.changes()).noneMatch(c -> c.path().contains(".."));

        // 3. System execution code
        when(mockLlm.generate(anyString(), anyString())).thenReturn("""
                {
                  "path": "src/main/java/com/linkforge/service/link/Bad.java",
                  "operation": "CREATE",
                  "proposedContent": "public class Bad { static { Runtime.getRuntime().exec(\\"rm -rf /\\"); } }"
                }
                """);
        ImplementationProposal prop3 = agentWithMock.propose("Add custom alias validation", List.of(), List.of(), null);
        assertThat(prop3.changes()).noneMatch(c -> c.proposedContent() != null && c.proposedContent().contains("Runtime.getRuntime()"));
    }

    @Test
    @DisplayName("Safety Boundary 23: Deterministic AliasValidator strictly enforces 3-30 character bounds and charset rules")
    void deterministicAliasValidationEnforces3to30Bounds() {
        // Minimum (3 characters)
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> AliasValidator.validate("abc"))).isNull();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> AliasValidator.validate("a_1"))).isNull();

        // Maximum (30 characters)
        String exact30 = "a".repeat(30);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> AliasValidator.validate(exact30))).isNull();

        // Out of range: too short (< 3)
        assertThatThrownBy(() -> AliasValidator.validate("ab"))
                .isInstanceOf(InvalidAliasException.class);

        // Out of range: too long (> 30)
        assertThatThrownBy(() -> AliasValidator.validate("a".repeat(31)))
                .isInstanceOf(InvalidAliasException.class);

        // Invalid characters
        assertThatThrownBy(() -> AliasValidator.validate("bad@alias"))
                .isInstanceOf(InvalidAliasException.class);
        assertThatThrownBy(() -> AliasValidator.validate("with spaces"))
                .isInstanceOf(InvalidAliasException.class);

        // Null and blank
        assertThatThrownBy(() -> AliasValidator.validate(""))
                .isInstanceOf(InvalidAliasException.class);
        assertThatThrownBy(() -> AliasValidator.validate("   "))
                .isInstanceOf(InvalidAliasException.class);
        assertThatThrownBy(() -> AliasValidator.validate(null))
                .isInstanceOf(InvalidAliasException.class);
    }

    @Test
    @DisplayName("Safety Boundary 24: Model proposals targeting pom.xml, configuration, wrappers, scripts, or test code are strictly rejected")
    void modelProposalsTargetingOutOfScopePathsStrictlyRejected() {
        LlmModelProvider mockLlm = mock(LlmModelProvider.class);
        ImplementationProposerAgent agentWithMock = new ImplementationProposerAgent(mockLlm, new ObjectMapper());

        List<String> outOfScopePayloads = List.of(
                // 1. pom.xml targeting / build configuration modification
                """
                {
                  "path": "pom.xml",
                  "operation": "MODIFY",
                  "proposedContent": "<project><build><plugins><plugin><artifactId>exec-maven-plugin</artifactId></plugin></plugins></build></project>"
                }
                """,
                // 2. application.properties configuration modification
                """
                {
                  "path": "src/main/resources/application.properties",
                  "operation": "MODIFY",
                  "proposedContent": "linkforge.execution.enabled=false"
                }
                """,
                // 3. Maven wrapper scripts
                """
                {
                  "path": "mvnw",
                  "operation": "MODIFY",
                  "proposedContent": "#!/bin/sh\\necho malicious"
                }
                """,
                // 4. Test code creation/tampering (generated tests must remain LinkForge-controlled)
                """
                {
                  "path": "src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java",
                  "operation": "MODIFY",
                  "proposedContent": "class FakeTest { @Test void pass() {} }"
                }
                """,
                // 5. Arbitrary other Java files
                """
                {
                  "path": "src/main/java/com/linkforge/service/link/UnauthorizedService.java",
                  "operation": "CREATE",
                  "proposedContent": "public class UnauthorizedService {}"
                }
                """
        );

        for (String payload : outOfScopePayloads) {
            when(mockLlm.generate(anyString(), anyString())).thenReturn(payload);
            ImplementationProposal proposal = agentWithMock.propose("Add custom alias validation", List.of(), List.of(), null);

            // The model proposal must be rejected, safely falling back to the deterministic proposal
            assertThat(proposal.supported()).isTrue();
            // None of the changes in the proposal should contain the out-of-scope path from the model
            assertThat(proposal.changes()).noneMatch(c -> "pom.xml".equals(c.path()));
            assertThat(proposal.changes()).noneMatch(c -> c.path().endsWith(".properties"));
            assertThat(proposal.changes()).noneMatch(c -> "mvnw".equals(c.path()));
            assertThat(proposal.changes()).noneMatch(c -> c.path().contains("UnauthorizedService"));
            // Any change with MODEL_SPECIALIST must not exist
            assertThat(proposal.changes()).noneMatch(c -> "MODEL_SPECIALIST".equals(c.specialistRole()));
        }
    }
}
