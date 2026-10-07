package com.linkforge.service.implementation;

import com.linkforge.agent.specialist.BuildDiagnosisSpecialistAgent;
import com.linkforge.agent.specialist.ImplementationRepairSpecialistAgent;
import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.PlanHasher;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.exception.WorkflowNotFoundException;
import com.linkforge.domain.workflow.implementation.AppliedFileChange;
import com.linkforge.domain.workflow.implementation.BuildDiagnosisResult;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.RepairAttemptRecord;
import com.linkforge.domain.workflow.implementation.RepairProposal;
import com.linkforge.domain.workflow.implementation.RollbackResult;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.implementation.container.ContainerBuildExecutor;
import com.linkforge.service.implementation.container.DockerContainerBuildExecutor;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.release.ReleaseReadinessService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing governed implementation proposal synthesis, approval enforcement,
 * isolated patch application, fixed Maven Wrapper validation, bounded diagnosis & repair,
 * verified rollback, and release readiness evaluation.
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
    private final BuildDiagnosisSpecialistAgent diagnosisAgent;
    private final ImplementationRepairSpecialistAgent repairAgent;
    private final ContainerBuildExecutor containerExecutor;
    private final ReleaseReadinessService releaseReadinessService;

    public record WorkspaceContext(
            Path workspacePath,
            Map<Path, byte[]> originalSnapshots,
            ImplementationProposal proposal
    ) {}

    private final Map<String, WorkspaceContext> activeWorkspaces = new ConcurrentHashMap<>();

    @Autowired
    public GovernedExecutionService(
            ImplementationProposerAgent proposerAgent,
            GovernedPatchApplier patchApplier,
            GovernedBuildValidator buildValidator,
            WorkflowRepository workflowRepository,
            CodebaseInspectionProperties inspectionProperties,
            GovernedExecutionProperties executionProperties,
            @Autowired(required = false) BuildDiagnosisSpecialistAgent diagnosisAgent,
            @Autowired(required = false) ImplementationRepairSpecialistAgent repairAgent,
            @Autowired(required = false) ContainerBuildExecutor containerExecutor,
            @Autowired(required = false) ReleaseReadinessService releaseReadinessService
    ) {
        this.proposerAgent = proposerAgent;
        this.patchApplier = patchApplier;
        this.buildValidator = buildValidator;
        this.workflowRepository = workflowRepository;
        this.inspectionProperties = inspectionProperties != null ? inspectionProperties : new CodebaseInspectionProperties();
        this.executionProperties = executionProperties != null ? executionProperties : new GovernedExecutionProperties();
        this.diagnosisAgent = diagnosisAgent != null ? diagnosisAgent : new BuildDiagnosisSpecialistAgent();
        this.repairAgent = repairAgent != null ? repairAgent : new ImplementationRepairSpecialistAgent();
        this.containerExecutor = containerExecutor != null ? containerExecutor : new DockerContainerBuildExecutor(this.executionProperties);
        this.releaseReadinessService = releaseReadinessService != null ? releaseReadinessService : new ReleaseReadinessService(workflowRepository);
    }

    public GovernedExecutionService(
            ImplementationProposerAgent proposerAgent,
            GovernedPatchApplier patchApplier,
            GovernedBuildValidator buildValidator,
            WorkflowRepository workflowRepository,
            CodebaseInspectionProperties inspectionProperties,
            GovernedExecutionProperties executionProperties,
            BuildDiagnosisSpecialistAgent diagnosisAgent,
            ImplementationRepairSpecialistAgent repairAgent
    ) {
        this(proposerAgent, patchApplier, buildValidator, workflowRepository, inspectionProperties, executionProperties, diagnosisAgent, repairAgent, null, null);
    }

    public GovernedExecutionService(
            ImplementationProposerAgent proposerAgent,
            GovernedPatchApplier patchApplier,
            GovernedBuildValidator buildValidator,
            WorkflowRepository workflowRepository,
            CodebaseInspectionProperties inspectionProperties,
            GovernedExecutionProperties executionProperties
    ) {
        this(proposerAgent, patchApplier, buildValidator, workflowRepository, inspectionProperties, executionProperties, null, null, null, null);
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

        // Require exact proposal hash for approval gate before any mutation
        run.setCurrentPlanHash(proposal.proposalHash());

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

        // 0. ENFORCE GENUINE CONTAINER ISOLATION FOR SUBMITTED REPOSITORIES
        // Untrusted submitted repository builds must never run directly on the host.
        // Requires operational container executor; fails safely if isolation is unavailable.
        boolean isBrownfield = run.getRepositoryPath() != null && !run.getRepositoryPath().isBlank();
        if (isBrownfield) {
            if (containerExecutor == null || !containerExecutor.isAvailable()) {
                String message = "Governed brownfield execution blocked: genuine container executor is not operational (real container isolation is unavailable). Host execution of external repositories is unconditionally blocked and disallowed to prevent executing untrusted wrappers or arbitrary build configurations.";
                run.addEvent(WorkflowEvent.of(
                        "MUTATION_BLOCKED",
                        WorkflowStage.BLOCKED.name(),
                        message
                ));
                run.transitionTo(WorkflowStatus.BLOCKED, WorkflowStage.BLOCKED);
                GovernedExecutionRecord record = new GovernedExecutionRecord(
                        executionId, run.getId(), "BLOCKED", WorkflowStage.BLOCKED.name(),
                        planHash, run.getImplementationProposal(), List.of(), null, null,
                        message, startedAt, Instant.now(), "BROWNFIELD"
                );
                run.setExecutionRecord(record);
                workflowRepository.save(run);
                throw new SafetyPolicyViolationException(message);
            }
        }

        String executionType = isBrownfield
                ? "BROWNFIELD"
                : (isTrueGreenfield(run) ? "TRUE_GREENFIELD" : "PREPARED_BASELINE_ENHANCEMENT");

        Map<String, String> initialRepoFingerprint = null;
        if (isBrownfield) {
            initialRepoFingerprint = captureRepoFingerprint(Path.of(run.getRepositoryPath()));
        }

        // 1. APPROVAL GATE ENFORCEMENT
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

        if (run.getStatus() == WorkflowStatus.COMPLETED && run.getExecutionRecord() != null && "COMPLETED".equals(run.getExecutionRecord().status())) {
            return run.getExecutionRecord();
        }

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

        // 2. ISOLATED DISPOSABLE WORKSPACE PREPARATION (Scenario-aware)
        Path tempWorkspace = null;
        boolean preserveWorkspaceForRepair = false;
        try {
            tempWorkspace = Files.createTempDirectory("linkforge-workspace-" + run.getId() + "-");
            prepareWorkspaceForScenario(run, tempWorkspace);
            Map<String, String> baselineFingerprint = captureRepoFingerprint(tempWorkspace);

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
                        ex.getMessage(), startedAt, Instant.now(), executionType,
                        baselineFingerprint, Collections.emptyMap(), null
                );
                run.setExecutionRecord(record);
                workflowRepository.save(run);
                return record;
            }

            Map<String, String> workspaceFingerprint = captureRepoFingerprint(tempWorkspace);
            String appliedDiff = generateUnifiedDiff(appResult.appliedChanges(), proposal);

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
                    "BROWNFIELD".equals(executionType)
                            ? "Executing isolated container build verification for brownfield workspace."
                            : "Executing Maven Wrapper build verification in isolated workspace."
            ));
            workflowRepository.save(run);

            BuildValidationResult buildResult;
            if ("BROWNFIELD".equals(executionType)) {
                buildResult = containerExecutor.executeIsolatedBuild(tempWorkspace, executionProperties.getFixedBuildCommand(), proposal);
            } else {
                buildResult = buildValidator.validateBuild(tempWorkspace, proposal);
                if (buildResult == null) {
                    buildResult = buildValidator.validateBuild(tempWorkspace);
                }
            }

            // 5. OUTCOME RESOLUTION & BOUNDED REPAIR LOOP
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
                        null, startedAt, Instant.now(), executionType,
                        baselineFingerprint, workspaceFingerprint, appliedDiff
                );
                run.setExecutionRecord(record);
                if (releaseReadinessService != null) {
                    releaseReadinessService.evaluateReleaseReadiness(run);
                }
                workflowRepository.save(run);
                return record;
            } else {
                run.addEvent(WorkflowEvent.of(
                        "VALIDATION_FAILED",
                        WorkflowStage.BUILD_VALIDATION.name(),
                        "Build validation FAILED (status: " + buildResult.status() +
                                ", exitCode: " + buildResult.exitCode() + ")."
                ));

                int attemptsMade = run.getRepairAttempts() != null ? run.getRepairAttempts().size() : 0;
                int maxAttempts = executionProperties.getMaxRepairAttempts();

                if (attemptsMade >= maxAttempts) {
                    run.addEvent(WorkflowEvent.of(
                            "ROLLBACK_STARTED",
                            WorkflowStage.ROLLED_BACK.name(),
                            "Repair attempts exhausted (" + attemptsMade + "/" + maxAttempts + "). Initiating rollback."
                    ));

                    RollbackResult rollbackResult = patchApplier.rollback(
                            appResult.originalSnapshots(),
                            "Repair attempts exhausted (" + attemptsMade + "/" + maxAttempts + ")"
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
                            "Workflow rolled back safely. Repair attempts exhausted; human intervention required."
                    ));

                    GovernedExecutionRecord record = new GovernedExecutionRecord(
                            executionId, run.getId(), "ROLLED_BACK", WorkflowStage.ROLLED_BACK.name(),
                            run.getCurrentPlanHash(), proposal, appResult.appliedChanges(), buildResult, rollbackResult,
                            "Repair attempts exhausted: human intervention required.", startedAt, Instant.now(), executionType,
                            baselineFingerprint, workspaceFingerprint, appliedDiff
                    );
                    run.setExecutionRecord(record);
                    if (releaseReadinessService != null) {
                        releaseReadinessService.evaluateReleaseReadiness(run);
                    }
                    workflowRepository.save(run);
                    return record;
                }

                BuildDiagnosisResult diagnosis = diagnosisAgent.diagnose(buildResult, proposal, run.getAcceptanceCriteria());
                run.setLastDiagnosis(diagnosis);
                run.addEvent(WorkflowEvent.of(
                        "BUILD_DIAGNOSED",
                        WorkflowStage.BUILD_VALIDATION.name(),
                        "Failure diagnosed: " + diagnosis.likelyCause() + " (" + diagnosis.affectedFiles().size() + " affected files)."
                ));

                RepairProposal repairProposal = repairAgent.createRepairProposal(
                        diagnosis, proposal, run.getRequirement(), run.getAcceptanceCriteria(), attemptsMade + 1, tempWorkspace
                );

                if (repairProposal == null || !repairProposal.safe() || !diagnosis.repairable()) {
                    String reason = (repairProposal != null && !repairProposal.safe())
                            ? repairProposal.unsafeReason()
                            : "Issue is not automatically repairable: " + diagnosis.likelyCause();

                    run.addEvent(WorkflowEvent.of(
                            "REPAIR_UNSAFE",
                            WorkflowStage.BUILD_VALIDATION.name(),
                            "Automated repair unsafe or unavailable: " + reason + ". Triggering rollback."
                    ));

                    RollbackResult rollbackResult = patchApplier.rollback(
                            appResult.originalSnapshots(),
                            "Automated repair unsafe: " + reason
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
                            "Workflow rolled back safely following unrepairable build validation failure."
                    ));

                    GovernedExecutionRecord record = new GovernedExecutionRecord(
                            executionId, run.getId(), "ROLLED_BACK", WorkflowStage.ROLLED_BACK.name(),
                            run.getCurrentPlanHash(), proposal, appResult.appliedChanges(), buildResult, rollbackResult,
                            "Build validation failed: " + reason, startedAt, Instant.now(), executionType,
                            baselineFingerprint, workspaceFingerprint, appliedDiff
                    );
                    run.setExecutionRecord(record);
                    if (releaseReadinessService != null) {
                        releaseReadinessService.evaluateReleaseReadiness(run);
                    }
                    workflowRepository.save(run);
                    return record;
                }

                // Safe repair proposed: pause for human approval
                run.setRepairProposal(repairProposal);
                run.setCurrentPlanHash(repairProposal.repairHash());
                run.setApproval(null);

                run.addEvent(WorkflowEvent.of(
                        "REPAIR_PROPOSED",
                        WorkflowStage.REPAIR_APPROVAL.name(),
                        "Repair proposal created (attempt " + repairProposal.attemptNumber() + ", hash: " +
                                repairProposal.repairHash() + "). Paused awaiting human approval."
                ));
                run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.REPAIR_APPROVAL);

                preserveWorkspaceForRepair = true;
                activeWorkspaces.put(run.getId(), new WorkspaceContext(tempWorkspace, appResult.originalSnapshots(), proposal));

                GovernedExecutionRecord record = new GovernedExecutionRecord(
                        executionId, run.getId(), "WAITING_FOR_APPROVAL", WorkflowStage.REPAIR_APPROVAL.name(),
                        repairProposal.repairHash(), proposal, appResult.appliedChanges(), buildResult, null,
                        null, startedAt, Instant.now(), executionType,
                        baselineFingerprint, workspaceFingerprint, appliedDiff
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
                    ex.getMessage(), startedAt, Instant.now(), executionType
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            return record;
        } finally {
            if (initialRepoFingerprint != null && run.getRepositoryPath() != null) {
                verifyRepoUnchanged(Path.of(run.getRepositoryPath()), initialRepoFingerprint);
            }
            if (tempWorkspace != null && !preserveWorkspaceForRepair) {
                cleanupWorkspace(tempWorkspace);
            }
        }
    }

    public GovernedExecutionRecord executeRepair(
            String workflowId,
            String repairHash,
            String decision,
            String approver,
            String comments
    ) {
        WorkflowRun run = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow '" + workflowId + "' was not found."));

        String executionId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        if (run.getCurrentStage() != WorkflowStage.REPAIR_APPROVAL || run.getStatus() != WorkflowStatus.WAITING_FOR_APPROVAL) {
            throw new IllegalStateException("Workflow '" + workflowId + "' is not waiting for repair approval. Current status: " +
                    run.getStatus() + ", stage: " + run.getCurrentStage());
        }

        if (repairHash == null || repairHash.isBlank() || !repairHash.trim().equals(run.getCurrentPlanHash())) {
            throw new InvalidPlanHashException("Submitted repair hash '" + (repairHash != null ? repairHash.trim() : "") +
                    "' does not match current repair proposal hash '" + run.getCurrentPlanHash() + "'.");
        }

        RepairProposal repairProposal = run.getRepairProposal();
        if (repairProposal == null) {
            throw new IllegalStateException("No active repair proposal found for workflow '" + workflowId + "'.");
        }

        String effectiveApprover = (approver != null && !approver.isBlank()) ? approver.trim() : "authorized-approver";
        String normalizedDecision = (decision != null) ? decision.trim().toUpperCase() : "REJECTED";

        WorkspaceContext wsContext = activeWorkspaces.get(workflowId);
        if (wsContext == null || !Files.isDirectory(wsContext.workspacePath())) {
            throw new IllegalStateException("Active workspace for workflow '" + workflowId + "' is not available.");
        }

        String executionType = (run.getRepositoryPath() != null && !run.getRepositoryPath().isBlank())
                ? "BROWNFIELD"
                : (isTrueGreenfield(run) ? "TRUE_GREENFIELD" : "PREPARED_BASELINE_ENHANCEMENT");

        if ("REJECTED".equals(normalizedDecision)) {
            run.addEvent(WorkflowEvent.of(
                    "REPAIR_REJECTED",
                    WorkflowStage.REPAIR_APPROVAL.name(),
                    "Repair hash " + repairHash.trim() + " rejected by " + effectiveApprover +
                            (comments != null && !comments.isBlank() ? ": " + comments : "")
            ));

            RollbackResult rollbackResult = patchApplier.rollback(
                    wsContext.originalSnapshots(),
                    "Repair rejected by human approver: " + effectiveApprover
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
                    "Workflow rolled back safely following repair proposal rejection."
            ));

            RepairAttemptRecord attemptRecord = new RepairAttemptRecord(
                    repairProposal.attemptNumber(),
                    run.getLastDiagnosis(),
                    repairProposal,
                    "REJECTED",
                    effectiveApprover,
                    null,
                    rollbackResult,
                    "REJECTED",
                    Instant.now()
            );
            run.addRepairAttempt(attemptRecord);

            activeWorkspaces.remove(workflowId);
            cleanupWorkspace(wsContext.workspacePath());

            GovernedExecutionRecord record = new GovernedExecutionRecord(
                    executionId, run.getId(), "ROLLED_BACK", WorkflowStage.ROLLED_BACK.name(),
                    repairHash.trim(), wsContext.proposal(), List.of(), null, rollbackResult,
                    "Repair rejected by " + effectiveApprover, startedAt, Instant.now()
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            return record;
        }

        // APPROVED
        run.setApproval(WorkflowApproval.of(run.getId(), "APPROVED", effectiveApprover, repairHash.trim(), comments));
        run.addEvent(WorkflowEvent.of(
                "REPAIR_APPROVED",
                WorkflowStage.REPAIR_APPROVAL.name(),
                "Repair hash " + repairHash.trim() + " approved by " + effectiveApprover
        ));

        try {
            run.transitionTo(WorkflowStatus.APPLYING, WorkflowStage.IMPLEMENTATION_APPLICATION);
            run.addEvent(WorkflowEvent.of(
                    "APPLICATION_STARTED",
                    WorkflowStage.IMPLEMENTATION_APPLICATION.name(),
                    "Applying " + repairProposal.changes().size() + " repaired changes in workspace."
            ));

            GovernedPatchApplier.ApplicationResult appResult = patchApplier.applyChanges(
                    wsContext.workspacePath(), repairProposal.changes()
            );

            run.addEvent(WorkflowEvent.of(
                    "APPLICATION_COMPLETED",
                    WorkflowStage.IMPLEMENTATION_APPLICATION.name(),
                    "Successfully applied repair file changes."
            ));

            run.transitionTo(WorkflowStatus.VALIDATING, WorkflowStage.BUILD_VALIDATION);
            run.addEvent(WorkflowEvent.of(
                    "VALIDATION_STARTED",
                    WorkflowStage.BUILD_VALIDATION.name(),
                    "Executing build verification for repair attempt " + repairProposal.attemptNumber() + "."
            ));

            BuildValidationResult buildResult;
            if ("BROWNFIELD".equals(executionType)) {
                buildResult = containerExecutor.executeIsolatedBuild(wsContext.workspacePath(), executionProperties.getFixedBuildCommand(), wsContext.proposal());
            } else {
                buildResult = buildValidator.validateBuild(wsContext.workspacePath(), wsContext.proposal());
                if (buildResult == null) {
                    buildResult = buildValidator.validateBuild(wsContext.workspacePath());
                }
            }

            RepairAttemptRecord attemptRecord = new RepairAttemptRecord(
                    repairProposal.attemptNumber(),
                    run.getLastDiagnosis(),
                    repairProposal,
                    "APPROVED",
                    effectiveApprover,
                    buildResult,
                    null,
                    buildResult.isSuccess() ? "SUCCEEDED" : "FAILED",
                    Instant.now()
            );
            run.addRepairAttempt(attemptRecord);

            if (buildResult.isSuccess()) {
                run.addEvent(WorkflowEvent.of(
                        "VALIDATION_COMPLETED",
                        WorkflowStage.BUILD_VALIDATION.name(),
                        "Repair validation SUCCEEDED (exit code 0, duration: " + buildResult.durationMs() + "ms)."
                ));
                run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
                run.addEvent(WorkflowEvent.of(
                        "WORKFLOW_COMPLETED",
                        WorkflowStage.FINISHED.name(),
                        "Governed repair and build verification completed successfully."
                ));

                activeWorkspaces.remove(workflowId);
                cleanupWorkspace(wsContext.workspacePath());

                GovernedExecutionRecord record = new GovernedExecutionRecord(
                        executionId, run.getId(), "COMPLETED", WorkflowStage.FINISHED.name(),
                        repairHash.trim(), wsContext.proposal(), appResult.appliedChanges(), buildResult, null,
                        null, startedAt, Instant.now(), executionType
                );
                run.setExecutionRecord(record);
                if (releaseReadinessService != null) {
                    releaseReadinessService.evaluateReleaseReadiness(run);
                }
                workflowRepository.save(run);
                return record;
            } else {
                run.addEvent(WorkflowEvent.of(
                        "VALIDATION_FAILED",
                        WorkflowStage.BUILD_VALIDATION.name(),
                        "Repair validation FAILED (status: " + buildResult.status() + ")."
                ));

                int totalAttempts = run.getRepairAttempts().size();
                int maxAttempts = executionProperties.getMaxRepairAttempts();

                if (totalAttempts >= maxAttempts) {
                    RollbackResult rollbackResult = patchApplier.rollback(
                            wsContext.originalSnapshots(),
                            "Repair attempts exhausted (" + totalAttempts + "/" + maxAttempts + ")"
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
                            "Repair attempts exhausted; human intervention required. Rolled back to baseline."
                    ));

                    activeWorkspaces.remove(workflowId);
                    cleanupWorkspace(wsContext.workspacePath());

                    GovernedExecutionRecord record = new GovernedExecutionRecord(
                            executionId, run.getId(), "ROLLED_BACK", WorkflowStage.ROLLED_BACK.name(),
                            repairHash.trim(), wsContext.proposal(), appResult.appliedChanges(), buildResult, rollbackResult,
                            "Repair attempts exhausted: human intervention required.", startedAt, Instant.now(), executionType
                    );
                    run.setExecutionRecord(record);
                    if (releaseReadinessService != null) {
                        releaseReadinessService.evaluateReleaseReadiness(run);
                    }
                    workflowRepository.save(run);
                    return record;
                } else {
                    BuildDiagnosisResult diagnosis = diagnosisAgent.diagnose(buildResult, wsContext.proposal(), run.getAcceptanceCriteria());
                    run.setLastDiagnosis(diagnosis);
                    RepairProposal nextRepair = repairAgent.createRepairProposal(
                            diagnosis, wsContext.proposal(), run.getRequirement(), run.getAcceptanceCriteria(), totalAttempts + 1, wsContext.workspacePath()
                    );

                    if (nextRepair == null || !nextRepair.safe() || !diagnosis.repairable()) {
                        RollbackResult rollbackResult = patchApplier.rollback(wsContext.originalSnapshots(), "Unsafe secondary repair");
                        run.transitionTo(WorkflowStatus.ROLLED_BACK, WorkflowStage.ROLLED_BACK);
                        activeWorkspaces.remove(workflowId);
                        cleanupWorkspace(wsContext.workspacePath());

                        GovernedExecutionRecord record = new GovernedExecutionRecord(
                                executionId, run.getId(), "ROLLED_BACK", WorkflowStage.ROLLED_BACK.name(),
                                repairHash.trim(), wsContext.proposal(), appResult.appliedChanges(), buildResult, rollbackResult,
                                "Next repair unsafe: human intervention required.", startedAt, Instant.now(), executionType
                        );
                        run.setExecutionRecord(record);
                        if (releaseReadinessService != null) {
                            releaseReadinessService.evaluateReleaseReadiness(run);
                        }
                        workflowRepository.save(run);
                        return record;
                    }

                    run.setRepairProposal(nextRepair);
                    run.setCurrentPlanHash(nextRepair.repairHash());
                    run.setApproval(null);
                    run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.REPAIR_APPROVAL);
                    run.addEvent(WorkflowEvent.of(
                            "REPAIR_PROPOSED",
                            WorkflowStage.REPAIR_APPROVAL.name(),
                            "Next repair attempt " + nextRepair.attemptNumber() + " proposed (hash: " + nextRepair.repairHash() + ")."
                    ));

                    GovernedExecutionRecord record = new GovernedExecutionRecord(
                            executionId, run.getId(), "WAITING_FOR_APPROVAL", WorkflowStage.REPAIR_APPROVAL.name(),
                            nextRepair.repairHash(), wsContext.proposal(), appResult.appliedChanges(), buildResult, null,
                            null, startedAt, Instant.now(), executionType
                    );
                    run.setExecutionRecord(record);
                    workflowRepository.save(run);
                    return record;
                }
            }
        } catch (Exception ex) {
            log.error("Fatal error during repair execution: {}", ex.getMessage(), ex);
            run.transitionTo(WorkflowStatus.FAILED, run.getCurrentStage());
            GovernedExecutionRecord record = new GovernedExecutionRecord(
                    executionId, run.getId(), "FAILED", run.getCurrentStage().name(),
                    repairHash.trim(), wsContext.proposal(), List.of(), null, null,
                    ex.getMessage(), startedAt, Instant.now(), executionType
            );
            run.setExecutionRecord(record);
            workflowRepository.save(run);
            return record;
        }
    }

    private void prepareWorkspaceForScenario(WorkflowRun run, Path tempWorkspace) throws IOException {
        if (run.getRepositoryPath() != null && !run.getRepositoryPath().isBlank()) {
            Path repoPath = Path.of(run.getRepositoryPath()).toAbsolutePath().normalize();
            if (Files.isDirectory(repoPath)) {
                copyDirectoryRecursively(repoPath, tempWorkspace);
            } else {
                copyBaseProjectToWorkspace(tempWorkspace);
            }
        } else if (isTrueGreenfield(run)) {
            copyMinimalSkeletonToWorkspace(tempWorkspace);
        } else {
            copyBaseProjectToWorkspace(tempWorkspace);
        }
    }

    private boolean isTrueGreenfield(WorkflowRun run) {
        if (run.getScenario() != Scenario.GREENFIELD) {
            return false;
        }
        ImplementationProposal proposal = run.getImplementationProposal();
        if (proposal == null || proposal.changes().isEmpty()) {
            return true;
        }
        return proposal.changes().stream().allMatch(c -> c.operation() == com.linkforge.domain.workflow.implementation.FileChangeOperation.CREATE);
    }

    private void copyMinimalSkeletonToWorkspace(Path tempWorkspace) throws IOException {
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

        // 4. Create empty standard directory structure without pre-existing LinkForge application classes
        Files.createDirectories(tempWorkspace.resolve("src/main/java"));
        Files.createDirectories(tempWorkspace.resolve("src/main/resources"));
        Files.createDirectories(tempWorkspace.resolve("src/test/java"));
        Files.createDirectories(tempWorkspace.resolve("src/test/resources"));
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

        // 4. Copy controlled source tree (src/main and application tests from src/test)
        Path srcMain = projectRoot.resolve("src/main");
        if (Files.isDirectory(srcMain)) {
            copyDirectoryRecursively(srcMain, tempWorkspace.resolve("src/main"));
        }

        Path srcTest = projectRoot.resolve("src/test");
        if (Files.isDirectory(srcTest)) {
            copyApplicationTestsRecursively(srcTest, tempWorkspace.resolve("src/test"));
        }
    }

    private void copyApplicationTestsRecursively(Path source, Path destination) throws IOException {
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path relative = source.relativize(sourcePath);
                String relStr = relative.toString().replace('\\', '/');
                if (Files.isDirectory(sourcePath)) {
                    if (relStr.contains("java/com/linkforge/agent")
                            || relStr.contains("java/com/linkforge/service/coordination")
                            || relStr.contains("java/com/linkforge/service/inspection")
                            || relStr.contains("java/com/linkforge/service/implementation")
                            || relStr.contains("java/com/linkforge/service/security")) {
                        return;
                    }
                    Path targetPath = destination.resolve(relative);
                    if (!Files.exists(targetPath)) {
                        Files.createDirectories(targetPath);
                    }
                } else {
                    if (relStr.contains("GovernedExecution")
                            || relStr.contains("Workflow")
                            || relStr.contains("Repeatable")
                            || relStr.contains("ModelBacked")
                            || relStr.contains("Specialist")
                            || relStr.contains("RealUrlShortenerPath")) {
                        return;
                    }
                    Path targetPath = destination.resolve(relative);
                    if (targetPath.getParent() != null && !Files.exists(targetPath.getParent())) {
                        Files.createDirectories(targetPath.getParent());
                    }
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Error copying test files: " + e.getMessage(), e);
            }
        });
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

    private Map<String, String> captureRepoFingerprint(Path repoPath) {
        Map<String, String> fingerprint = new ConcurrentHashMap<>();
        if (Files.isDirectory(repoPath)) {
            try {
                Files.walk(repoPath).filter(Files::isRegularFile).forEach(p -> {
                    try {
                        fingerprint.put(repoPath.relativize(p).toString(), GovernedPatchApplier.computeSha256(Files.readAllBytes(p)));
                    } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
        return fingerprint;
    }

    private void verifyRepoUnchanged(Path repoPath, Map<String, String> initial) {
        Map<String, String> current = captureRepoFingerprint(repoPath);
        if (!initial.equals(current)) {
            throw new SafetyPolicyViolationException("Original submitted repository was modified during execution.");
        }
    }

    private String generateUnifiedDiff(
            List<AppliedFileChange> appliedChanges,
            ImplementationProposal proposal
    ) {
        if (appliedChanges == null || appliedChanges.isEmpty()) {
            return "";
        }
        Map<String, String> contentByPath = new HashMap<>();
        if (proposal != null && proposal.changes() != null) {
            for (var c : proposal.changes()) {
                if (c.path() != null && c.patchContent() != null) {
                    contentByPath.put(c.path(), c.patchContent());
                }
            }
        }
        StringBuilder diff = new StringBuilder();
        for (AppliedFileChange change : appliedChanges) {
            diff.append("--- a/").append(change.path()).append("\n");
            diff.append("+++ b/").append(change.path()).append("\n");
            String content = contentByPath.get(change.path());
            if (content != null) {
                long lineCount = content.lines().count();
                diff.append("@@ -0,0 +1,").append(lineCount).append(" @@\n");
                content.lines().forEach(l -> diff.append("+").append(l).append("\n"));
            } else {
                diff.append("@@ -0,0 +1,1 @@ [").append(change.operation()).append("]\n");
            }
        }
        return diff.toString();
    }
}
