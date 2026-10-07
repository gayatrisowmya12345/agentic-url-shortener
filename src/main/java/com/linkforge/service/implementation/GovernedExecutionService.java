package com.linkforge.service.implementation;

import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.PlanHasher;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.exception.WorkflowNotFoundException;
import com.linkforge.domain.workflow.implementation.AppliedFileChange;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.RollbackResult;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.security.InvalidPlanHashException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service managing governed implementation proposal synthesis, approval enforcement,
 * isolated patch application, fixed Maven Wrapper validation, and verified rollback.
 */
@Service
public class GovernedExecutionService {

    private static final Logger log = LoggerFactory.getLogger(GovernedExecutionService.class);

    private final ImplementationProposerAgent proposerAgent;
    private final GovernedPatchApplier patchApplier;
    private final GovernedBuildValidator buildValidator;
    private final WorkflowRepository workflowRepository;
    private final CodebaseInspectionProperties inspectionProperties;
    private final GovernedExecutionProperties executionProperties;

    @Autowired
    public GovernedExecutionService(
            ImplementationProposerAgent proposerAgent,
            GovernedPatchApplier patchApplier,
            GovernedBuildValidator buildValidator,
            WorkflowRepository workflowRepository,
            CodebaseInspectionProperties inspectionProperties,
            GovernedExecutionProperties executionProperties
    ) {
        this.proposerAgent = proposerAgent;
        this.patchApplier = patchApplier;
        this.buildValidator = buildValidator;
        this.workflowRepository = workflowRepository;
        this.inspectionProperties = inspectionProperties != null ? inspectionProperties : new CodebaseInspectionProperties();
        this.executionProperties = executionProperties != null ? executionProperties : new GovernedExecutionProperties();
    }

    public ImplementationProposal proposeImplementation(WorkflowRun run) {
        if (run == null) {
            throw new IllegalArgumentException("WorkflowRun cannot be null.");
        }

        ImplementationProposal proposal = proposerAgent.propose(
                run.getRequirement(),
                run.getAcceptanceCriteria(),
                run.getTasks(),
                run.getRepositoryEvidence()
        );

        run.setImplementationProposal(proposal);

        // Incorporate proposal hash into current plan hash so approval covers proposed code
        String combinedPlanHash = PlanHasher.computePlanHash(run.getTasks(), proposal.proposalHash());
        run.setCurrentPlanHash(combinedPlanHash);

        if (proposal.supported()) {
            run.addEvent(WorkflowEvent.of(
                    "IMPLEMENTATION_PROPOSED",
                    WorkflowStage.IMPLEMENTATION_PROPOSAL.name(),
                    "Implementation proposed for scope " + proposal.scope() + " (" +
                            proposal.changes().size() + " file changes). Proposal hash: " + proposal.proposalHash()
            ));
            run.addAgentDecision(AgentDecision.of(
                    "implementation-proposer",
                    proposal.scope(),
                    "PROPOSED",
                    Map.of(
                            "proposalId", proposal.proposalId(),
                            "supported", true,
                            "proposalHash", proposal.proposalHash(),
                            "fileCount", proposal.changes().size()
                    )
            ));
        } else {
            run.addEvent(WorkflowEvent.of(
                    "IMPLEMENTATION_SCOPE_UNSUPPORTED",
                    WorkflowStage.IMPLEMENTATION_PROPOSAL.name(),
                    proposal.unsupportedReason()
            ));
            run.addAgentDecision(AgentDecision.of(
                    "implementation-proposer",
                    "UNSUPPORTED",
                    "REJECTED_OUT_OF_SCOPE",
                    Map.of(
                            "proposalId", proposal.proposalId(),
                            "supported", false,
                            "reason", proposal.unsupportedReason()
                    )
            ));
        }

        return proposal;
    }

    public GovernedExecutionRecord executeImplementation(String workflowId, String planHash) {
        WorkflowRun run = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow '" + workflowId + "' was not found."));

        String executionId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        // 0. PREVENT EXECUTION OF UNTRUSTED SUBMITTED REPOSITORY BUILD INPUTS
        if (run.getRepositoryPath() != null && !run.getRepositoryPath().isBlank()) {
            String message = "Governed execution of submitted repository paths is disallowed to prevent executing untrusted wrappers or build configuration.";
            run.addEvent(WorkflowEvent.of(
                    "MUTATION_BLOCKED",
                    WorkflowStage.BLOCKED.name(),
                    message
            ));
            run.transitionTo(WorkflowStatus.BLOCKED, WorkflowStage.BLOCKED);
            GovernedExecutionRecord record = new GovernedExecutionRecord(
                    executionId, run.getId(), "BLOCKED", WorkflowStage.BLOCKED.name(),
                    planHash, run.getImplementationProposal(), List.of(), null, null,
                    message, startedAt, Instant.now()
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            throw new SafetyPolicyViolationException(message);
        }

        // 1. APPROVAL GATE ENFORCEMENT
        // If rejected
        if (run.getStatus() == WorkflowStatus.REJECTED
                || (run.getApproval() != null && "REJECTED".equalsIgnoreCase(run.getApproval().decision()))) {
            run.addEvent(WorkflowEvent.of(
                    "MUTATION_BLOCKED",
                    WorkflowStage.PLAN_APPROVAL.name(),
                    "Execution blocked: Plan was explicitly rejected by human approver."
            ));
            run.transitionTo(WorkflowStatus.BLOCKED, WorkflowStage.BLOCKED);
            GovernedExecutionRecord record = new GovernedExecutionRecord(
                    executionId, run.getId(), "BLOCKED", WorkflowStage.BLOCKED.name(),
                    planHash, run.getImplementationProposal(), List.of(), null, null,
                    "Plan was rejected by human approver.", startedAt, Instant.now()
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            throw new IllegalStateException("Execution blocked: Plan was rejected by human approver.");
        }

        // If approval missing or stale
        if (run.getApproval() == null || !"APPROVED".equalsIgnoreCase(run.getApproval().decision())
                || (run.getCurrentPlanHash() != null && !run.getCurrentPlanHash().equals(run.getApproval().planHash()))) {
            run.addEvent(WorkflowEvent.of(
                    "MUTATION_BLOCKED",
                    WorkflowStage.PLAN_APPROVAL.name(),
                    "Execution blocked: Valid human approval is required before source mutation."
            ));
            run.transitionTo(WorkflowStatus.BLOCKED, WorkflowStage.BLOCKED);
            GovernedExecutionRecord record = new GovernedExecutionRecord(
                    executionId, run.getId(), "BLOCKED", WorkflowStage.BLOCKED.name(),
                    planHash, run.getImplementationProposal(), List.of(), null, null,
                    "Valid human approval is required before source mutation.", startedAt, Instant.now()
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            throw new IllegalStateException("Execution blocked: Human approval is required before source mutation.");
        }

        // If plan hash does not match approved plan hash or is missing
        if (planHash == null || planHash.isBlank() || !planHash.trim().equals(run.getCurrentPlanHash())) {
            run.addEvent(WorkflowEvent.of(
                    "MUTATION_BLOCKED",
                    WorkflowStage.PLAN_APPROVAL.name(),
                    "Execution blocked: Provided plan hash '" + (planHash != null ? planHash.trim() : "") +
                            "' does not match approved plan hash '" + run.getCurrentPlanHash() + "'."
            ));
            run.transitionTo(WorkflowStatus.BLOCKED, WorkflowStage.BLOCKED);
            GovernedExecutionRecord record = new GovernedExecutionRecord(
                    executionId, run.getId(), "BLOCKED", WorkflowStage.BLOCKED.name(),
                    planHash, run.getImplementationProposal(), List.of(), null, null,
                    "Plan hash mismatch.", startedAt, Instant.now()
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            throw new InvalidPlanHashException("Submitted plan hash does not match approved plan hash.");
        }

        // Check supported proposal presence
        ImplementationProposal proposal = run.getImplementationProposal();
        if (proposal == null || !proposal.supported()) {
            String reason = proposal != null ? proposal.unsupportedReason() : "No implementation proposal found.";
            run.addEvent(WorkflowEvent.of(
                    "MUTATION_BLOCKED",
                    WorkflowStage.IMPLEMENTATION_PROPOSAL.name(),
                    "Execution blocked: " + reason
            ));
            run.transitionTo(WorkflowStatus.BLOCKED, WorkflowStage.BLOCKED);
            GovernedExecutionRecord record = new GovernedExecutionRecord(
                    executionId, run.getId(), "BLOCKED", WorkflowStage.BLOCKED.name(),
                    planHash, proposal, List.of(), null, null,
                    reason, startedAt, Instant.now()
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            throw new IllegalStateException("Execution blocked: " + reason);
        }

        // 2. ISOLATED DISPOSABLE WORKSPACE PREPARATION
        Path tempWorkspace = null;
        try {
            tempWorkspace = Files.createTempDirectory("linkforge-workspace-" + run.getId() + "-");
            copyBaseProjectToWorkspace(tempWorkspace);

            // 3. APPLICATION STAGE
            run.transitionTo(WorkflowStatus.APPLYING, WorkflowStage.IMPLEMENTATION_APPLICATION);
            run.addEvent(WorkflowEvent.of(
                    "APPLICATION_STARTED",
                    WorkflowStage.IMPLEMENTATION_APPLICATION.name(),
                    "Applying " + proposal.changes().size() + " proposed changes in isolated disposable workspace."
            ));
            workflowRepository.save(run);

            GovernedPatchApplier.ApplicationResult appResult;
            try {
                appResult = patchApplier.applyChanges(tempWorkspace, proposal.changes());
            } catch (SafetyPolicyViolationException | StaleInputHashException ex) {
                log.warn("Safety or concurrency violation applying changes: {}", ex.getMessage());
                run.addEvent(WorkflowEvent.of(
                        "APPLICATION_FAILED",
                        WorkflowStage.IMPLEMENTATION_APPLICATION.name(),
                        "Safety boundary violation: " + ex.getMessage()
                ));
                run.transitionTo(WorkflowStatus.BLOCKED, WorkflowStage.BLOCKED);
                GovernedExecutionRecord record = new GovernedExecutionRecord(
                        executionId, run.getId(), "BLOCKED", WorkflowStage.BLOCKED.name(),
                        planHash, proposal, List.of(), null, null,
                        ex.getMessage(), startedAt, Instant.now()
                );
                run.setExecutionRecord(record);
                workflowRepository.save(run);
                return record;
            }

            run.addEvent(WorkflowEvent.of(
                    "APPLICATION_COMPLETED",
                    WorkflowStage.IMPLEMENTATION_APPLICATION.name(),
                    "Successfully applied " + appResult.appliedChanges().size() + " file changes atomically."
            ));

            // 4. VALIDATION STAGE
            run.transitionTo(WorkflowStatus.VALIDATING, WorkflowStage.BUILD_VALIDATION);
            run.addEvent(WorkflowEvent.of(
                    "VALIDATION_STARTED",
                    WorkflowStage.BUILD_VALIDATION.name(),
                    "Executing Maven Wrapper build verification in isolated workspace."
            ));
            workflowRepository.save(run);

            BuildValidationResult buildResult = buildValidator.validateBuild(tempWorkspace);

            // 5. OUTCOME RESOLUTION & ROLLBACK
            if (buildResult.isSuccess()) {
                run.addEvent(WorkflowEvent.of(
                        "VALIDATION_COMPLETED",
                        WorkflowStage.BUILD_VALIDATION.name(),
                        "Build validation SUCCEEDED (exit code 0, duration: " + buildResult.durationMs() + "ms)."
                ));
                run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
                run.addEvent(WorkflowEvent.of(
                        "WORKFLOW_COMPLETED",
                        WorkflowStage.FINISHED.name(),
                        "Governed implementation and build verification completed successfully."
                ));

                GovernedExecutionRecord record = new GovernedExecutionRecord(
                        executionId, run.getId(), "COMPLETED", WorkflowStage.FINISHED.name(),
                        run.getCurrentPlanHash(), proposal, appResult.appliedChanges(), buildResult, null,
                        null, startedAt, Instant.now()
                );
                run.setExecutionRecord(record);
                workflowRepository.save(run);
                return record;
            } else {
                // Build validation failed: trigger verified rollback
                run.addEvent(WorkflowEvent.of(
                        "VALIDATION_FAILED",
                        WorkflowStage.BUILD_VALIDATION.name(),
                        "Build validation FAILED (status: " + buildResult.status() +
                                ", exitCode: " + buildResult.exitCode() + "). Triggering rollback."
                ));

                run.addEvent(WorkflowEvent.of(
                        "ROLLBACK_STARTED",
                        WorkflowStage.ROLLED_BACK.name(),
                        "Initiating verified rollback of applied mutations in workspace."
                ));

                RollbackResult rollbackResult = patchApplier.rollback(
                        appResult.originalSnapshots(),
                        "Build validation failed with status " + buildResult.status()
                );

                run.addEvent(WorkflowEvent.of(
                        "ROLLBACK_VERIFIED",
                        WorkflowStage.ROLLED_BACK.name(),
                        "Rollback verified: Restored " + rollbackResult.restoredFiles().size() + " files with verified hashes."
                ));

                run.transitionTo(WorkflowStatus.ROLLED_BACK, WorkflowStage.ROLLED_BACK);
                run.addEvent(WorkflowEvent.of(
                        "WORKFLOW_ROLLED_BACK",
                        WorkflowStage.ROLLED_BACK.name(),
                        "Workflow rolled back safely following build validation failure."
                ));

                GovernedExecutionRecord record = new GovernedExecutionRecord(
                        executionId, run.getId(), "ROLLED_BACK", WorkflowStage.ROLLED_BACK.name(),
                        run.getCurrentPlanHash(), proposal, appResult.appliedChanges(), buildResult, rollbackResult,
                        "Build validation failed: " + buildResult.status(), startedAt, Instant.now()
                );
                run.setExecutionRecord(record);
                workflowRepository.save(run);
                return record;
            }

        } catch (Exception ex) {
            log.error("Fatal error during governed implementation execution: {}", ex.getMessage(), ex);
            run.addEvent(WorkflowEvent.of(
                    "EXECUTION_FAILED",
                    run.getCurrentStage().name(),
                    "Fatal execution failure: " + ex.getMessage()
            ));
            run.transitionTo(WorkflowStatus.FAILED, run.getCurrentStage());
            GovernedExecutionRecord record = new GovernedExecutionRecord(
                    executionId, run.getId(), "FAILED", run.getCurrentStage().name(),
                    planHash, proposal, List.of(), null, null,
                    ex.getMessage(), startedAt, Instant.now()
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            return record;
        } finally {
            if (tempWorkspace != null) {
                cleanupWorkspace(tempWorkspace);
            }
        }
    }

    private void copyBaseProjectToWorkspace(Path tempWorkspace) throws IOException {
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();

        // 1. Copy pom.xml
        Path pom = projectRoot.resolve("pom.xml");
        if (Files.exists(pom)) {
            Files.copy(pom, tempWorkspace.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
        }

        // 2. Copy mvnw and mvnw.cmd
        Path mvnw = projectRoot.resolve("mvnw");
        if (Files.exists(mvnw)) {
            Path targetMvnw = tempWorkspace.resolve("mvnw");
            Files.copy(mvnw, targetMvnw, StandardCopyOption.REPLACE_EXISTING);
            targetMvnw.toFile().setExecutable(true, false);
            targetMvnw.toFile().setReadable(true, false);
        }

        Path mvnwCmd = projectRoot.resolve("mvnw.cmd");
        if (Files.exists(mvnwCmd)) {
            Files.copy(mvnwCmd, tempWorkspace.resolve("mvnw.cmd"), StandardCopyOption.REPLACE_EXISTING);
        }

        // 3. Copy .mvn
        Path dotMvn = projectRoot.resolve(".mvn");
        if (Files.isDirectory(dotMvn)) {
            copyDirectoryRecursively(dotMvn, tempWorkspace.resolve(".mvn"));
        }

        // 4. Copy controlled source tree (src/main and src/test)
        Path src = projectRoot.resolve("src");
        if (Files.isDirectory(src)) {
            copyDirectoryRecursively(src, tempWorkspace.resolve("src"));
        }
    }

    private void copyDirectoryRecursively(Path source, Path destination) throws IOException {
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = destination.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    if (!Files.exists(targetPath)) {
                        Files.createDirectories(targetPath);
                    }
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Error copying source files: " + e.getMessage(), e);
            }
        });
    }

    private void cleanupWorkspace(Path workspace) {
        try {
            if (Files.exists(workspace)) {
                Files.walk(workspace)
                        .sorted((p1, p2) -> -p1.compareTo(p2))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {}
                        });
            }
        } catch (Exception e) {
            log.warn("Failed to cleanly delete temporary workspace {}: {}", workspace, e.getMessage());
        }
    }
}
