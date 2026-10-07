package com.linkforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.agent.specialist.BuildDiagnosisSpecialistAgent;
import com.linkforge.agent.specialist.ImplementationRepairSpecialistAgent;
import com.linkforge.api.dto.WorkflowEvidenceResponse;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.service.implementation.GovernedBuildValidator;
import com.linkforge.service.implementation.ImplementationProposerAgent;
import com.linkforge.domain.workflow.implementation.BuildDiagnosisResult;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.FileChangeOperation;
import com.linkforge.domain.workflow.implementation.FileChangeProposal;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.RepairProposal;
import com.linkforge.domain.workflow.implementation.RollbackResult;
import com.linkforge.domain.workflow.implementation.TestReportItem;
import com.linkforge.service.WorkflowOrchestrator;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.evidence.WorkflowEvidenceService;
import com.linkforge.service.implementation.GovernedExecutionProperties;
import com.linkforge.service.implementation.GovernedExecutionService;
import com.linkforge.service.implementation.GovernedPatchApplier;
import com.linkforge.service.link.LinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests verifying real URL-shortener path behavior
 * as mandated by reviewer requirements:
 * 1. Requirement-derived alias behavior & real HTTP API test in governed workspace
 * 2. Connected implementation, test evidence, criterion lineage, and zero-relevant-tests accounting
 * 3. Safe brownfield execution: container isolation probe & repository immutability
 * 4. True greenfield execution from empty source baseline vs prepared-baseline enhancement
 * 5. Build failure diagnosis, corrective repair proposal, repair approval, and bounded rollback/exhaustion
 */
@SpringBootTest
@AutoConfigureMockMvc
class RealUrlShortenerPathIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowOrchestrator orchestrator;

    @Autowired
    private GovernedExecutionService governedExecutionService;

    @Autowired
    private WorkflowEvidenceService evidenceService;

    @Autowired
    private GovernedExecutionProperties executionProperties;

    @Autowired
    private BuildDiagnosisSpecialistAgent diagnosisAgent;

    @Autowired
    private ImplementationRepairSpecialistAgent repairAgent;

    @Autowired
    private GovernedPatchApplier patchApplier;

    @Autowired
    private ImplementationProposerAgent proposerAgent;

    @Autowired
    private com.linkforge.service.inspection.CodebaseInspectionProperties inspectionProperties;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        linkRepository.clear();
    }

    @Test
    @DisplayName("Requirement 1 & 2: Governed proposal derives min length 4, and governed workspace HTTP API tests reject 3-char and accept 4-char aliases")
    void httpApiEnforcesRequirementDerivedAliasLength() throws Exception {
        // Step 1: Submit requirement explicitly specifying minimum alias length of 4 characters
        String reqPayload = """
                {
                  "requirement": "Require custom link aliases to have a minimum length of 4 characters and maximum length of 30 characters"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        // Step 2: Approve plan to trigger specialist coordination and implementation proposal
        MvcResult approvePlanResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andReturn();

        WorkflowRun run = workflowRepository.findById(id).orElseThrow();
        ImplementationProposal proposal = run.getImplementationProposal();
        assertThat(proposal).isNotNull();
        assertThat(proposal.supported()).isTrue();

        // Verify proposed AliasValidator enforces requirement-derived MIN_LENGTH = 4 (not hardcoded 3)
        FileChangeProposal validatorChange = proposal.changes().stream()
                .filter(c -> c.path().contains("AliasValidator.java"))
                .findFirst().orElseThrow();
        assertThat(validatorChange.proposedContent()).contains("MIN_LENGTH = 4;");
        assertThat(validatorChange.proposedContent()).contains("MAX_LENGTH = 30;");
        assertThat(validatorChange.proposedContent()).contains("{4,30}");

        // Verify proposed HTTP test exercises MockMvc in governed workspace testing 3-char ("aaa") and 4-char ("aaaa") aliases
        FileChangeProposal httpTestChange = proposal.changes().stream()
                .filter(c -> c.path().contains("CustomAliasHttpValidationTest.java"))
                .findFirst().orElseThrow();
        assertThat(httpTestChange.proposedContent()).contains("rejectsAliasShorterThanMinimumViaHttpApi");
        assertThat(httpTestChange.proposedContent()).contains("acceptsAliasAtMinimumLengthViaHttpApi");
        assertThat(httpTestChange.proposedContent()).contains("repeat(Math.max(1, %d - 1))".replace("%d", "4")); // "aaa" (3 chars, not "ab")
        assertThat(httpTestChange.proposedContent()).contains("repeat(4)"); // "aaaa" (4 chars)

        // Step 3: Approve proposal to execute implementation in isolated governed workspace
        String proposalHash = objectMapper.readTree(approvePlanResult.getResponse().getContentAsString()).get("planHash").asText();
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", proposalHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"));

        // Step 4: Verify governed execution records and test evidence produced in the governed workspace
        WorkflowRun executedRun = workflowRepository.findById(id).orElseThrow();
        GovernedExecutionRecord record = executedRun.getExecutionRecord();
        assertThat(record).isNotNull();
        assertThat(record.status()).isEqualTo("COMPLETED");
        assertThat(record.executionType()).isEqualTo("PREPARED_BASELINE_ENHANCEMENT");
        assertThat(record.buildValidation().isSuccess()).isTrue();

        // Verify discovered test reports: proves rejection of 3-char alias ("aaa") and acceptance of 4-char alias ("aaaa")
        List<TestReportItem> testReports = record.buildValidation().testReports();
        assertThat(testReports).isNotEmpty();

        TestReportItem reject3Test = testReports.stream()
                .filter(t -> t.testName().equals("rejectsAliasShorterThanMinimumViaHttpApi"))
                .findFirst().orElseThrow();
        assertThat(reject3Test.isPassed()).isTrue();
        assertThat(reject3Test.testSuite()).contains("CustomAliasHttpValidationTest");
        assertThat(reject3Test.criterionLineage()).contains("AC-1");

        TestReportItem accept4Test = testReports.stream()
                .filter(t -> t.testName().equals("acceptsAliasAtMinimumLengthViaHttpApi"))
                .findFirst().orElseThrow();
        assertThat(accept4Test.isPassed()).isTrue();
        assertThat(accept4Test.testSuite()).contains("CustomAliasHttpValidationTest");
        assertThat(accept4Test.criterionLineage()).contains("AC-1");

        // Step 5: Check evidence response labels prepared-baseline enhancement and reports full verification
        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(executedRun);
        assertThat(evidence.verificationStatus().scenarioClassification()).isEqualTo("PREPARED_BASELINE_ENHANCEMENT");
        assertThat(evidence.verificationStatus().automatedTestExecution()).isEqualTo("VERIFIED (FULL_VERIFICATION)");
    }

    @Test
    @DisplayName("Requirement 4: True greenfield execution from empty source baseline creates production and test source, validates build, and generates evidence")
    void trueGreenfieldExecutionFromEmptySourceBaseline() throws Exception {
        String reqPayload = """
                {
                  "requirement": "Build a greenfield standalone URL shortener from an empty source baseline"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        // Phase 1 approval: Plan -> Proposal
        MvcResult approvePlanResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andReturn();

        WorkflowRun run = workflowRepository.findById(id).orElseThrow();
        ImplementationProposal proposal = run.getImplementationProposal();
        assertThat(proposal).isNotNull();
        assertThat(proposal.supported()).isTrue();

        // Verify all changes are CREATE operations (empty source baseline)
        assertThat(proposal.changes()).allMatch(c -> c.operation() == FileChangeOperation.CREATE);
        assertThat(proposal.changes().stream().anyMatch(c -> c.path().contains("GreenfieldApplication.java"))).isTrue();
        assertThat(proposal.changes().stream().anyMatch(c -> c.path().contains("StandaloneUrlShortener.java"))).isTrue();
        assertThat(proposal.changes().stream().anyMatch(c -> c.path().contains("StandaloneUrlShortenerTest.java"))).isTrue();

        // Phase 2 approval: Proposal -> Isolated execution from empty skeleton
        String proposalHash = objectMapper.readTree(approvePlanResult.getResponse().getContentAsString()).get("planHash").asText();
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", proposalHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"));

        WorkflowRun executedRun = workflowRepository.findById(id).orElseThrow();
        GovernedExecutionRecord record = executedRun.getExecutionRecord();
        assertThat(record).isNotNull();
        assertThat(record.executionType()).isEqualTo("TRUE_GREENFIELD");
        assertThat(record.buildValidation().isSuccess()).isTrue();

        // Discovered greenfield test report evidence (unit shortener test and full HTTP API integration test)
        List<TestReportItem> testReports = record.buildValidation().testReports();
        assertThat(testReports.stream().anyMatch(t -> t.testSuite().contains("StandaloneUrlShortenerTest") && t.isPassed())).isTrue();
        assertThat(testReports.stream().anyMatch(t -> t.testSuite().contains("GreenfieldHttpIntegrationTest") && t.isPassed())).isTrue();
        assertThat(record.appliedChanges()).anyMatch(c -> c.path().contains("GreenfieldShortenerController.java"));

        // Evidence verification status classification
        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(executedRun);
        assertThat(evidence.verificationStatus().scenarioClassification()).isEqualTo("TRUE_GREENFIELD");
        assertThat(evidence.verificationStatus().sourceCodeGeneration()).isEqualTo("VERIFIED (ISOLATED_PROPOSAL)");
        assertThat(evidence.verificationStatus().buildExecution()).isEqualTo("VERIFIED (MAVEN_WRAPPER_BUILD)");
        assertThat(evidence.verificationStatus().automatedTestExecution()).isEqualTo("VERIFIED (FULL_VERIFICATION)");
    }

    @Test
    @DisplayName("Requirement 3: Brownfield execution blocks when container isolation runtime is unavailable, and submitted repo remains untouched")
    void brownfieldExecutionBlockedWhenContainerIsolationUnavailable() throws Exception {
        Path repoRoot = tempDir.resolve("external-brownfield-repo");
        Files.createDirectories(repoRoot.resolve("src/main/java"));
        Path sourceFile = repoRoot.resolve("src/main/java/App.java");
        String originalSource = "package com.example; public class App { int secret = 99; }";
        Files.writeString(sourceFile, originalSource);

        Path untrustedWrapper = repoRoot.resolve("mvnw");
        Files.writeString(untrustedWrapper, "#!/bin/sh\necho 'MALICIOUS SCRIPT'\nexit 1\n");
        untrustedWrapper.toFile().setExecutable(true, false);

        byte[] originalSourceBytes = Files.readAllBytes(sourceFile);
        byte[] originalWrapperBytes = Files.readAllBytes(untrustedWrapper);

        // 1. Submit workflow with external brownfield repository path
        WorkflowRun run = workflowRepository.save(new WorkflowRun("Refactor existing brownfield repository to add custom alias support", repoRoot.toString()));

        // Read-only inspection works
        ImplementationProposal proposal = governedExecutionService.proposeImplementation(run);
        assertThat(proposal.supported()).isTrue();

        // 2. Set plan hash and approval
        run.setCurrentPlanHash(proposal.proposalHash());
        run.setApproval(com.linkforge.domain.workflow.WorkflowApproval.of(run.getId(), "APPROVED", "approver", proposal.proposalHash(), "approved"));
        workflowRepository.save(run);

        // 3. Execution attempt is blocked because container isolation is probed as unavailable on host
        assertThat(executionProperties.isContainerIsolationAvailable()).isFalse();

        org.junit.jupiter.api.Assertions.assertThrows(
                com.linkforge.service.implementation.SafetyPolicyViolationException.class,
                () -> governedExecutionService.executeImplementation(run.getId(), proposal.proposalHash())
        );

        WorkflowRun blockedRun = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(blockedRun.getStatus()).isEqualTo(WorkflowStatus.BLOCKED);
        assertThat(blockedRun.getCurrentStage()).isEqualTo(WorkflowStage.BLOCKED);
        assertThat(blockedRun.getExecutionRecord().status()).isEqualTo("BLOCKED");
        assertThat(blockedRun.getExecutionRecord().failureReason()).contains("real container isolation is unavailable");

        // 4. Verify original repository and caller-supplied wrapper remain 100% untouched
        assertThat(Files.readAllBytes(sourceFile)).isEqualTo(originalSourceBytes);
        assertThat(Files.readAllBytes(untrustedWrapper)).isEqualTo(originalWrapperBytes);
    }

    @Test
    @DisplayName("Requirement 4: End-to-end repair workflow diagnoses failure, proposes corrective repair, approves, re-verifies in isolated workspace, and records final outcome")
    void endToEndRepairWorkflowSucceedsOnApproval() {
        // Build validator that simulates a compilation error on initial execution, and success on repair re-verification
        GovernedBuildValidator testValidator = org.mockito.Mockito.mock(GovernedBuildValidator.class);
        org.mockito.Mockito.when(testValidator.validateBuild(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        new BuildValidationResult(
                                "./mvnw --batch-mode clean verify",
                                1,
                                2100,
                                "[ERROR] COMPILATION ERROR : /src/main/java/com/linkforge/service/link/AliasValidator.java:[10,5] ';' expected",
                                "BUILD_FAILED",
                                Instant.now(),
                                true,
                                List.of()
                        ),
                        new BuildValidationResult(
                                "./mvnw --batch-mode clean verify",
                                0,
                                3200,
                                "[INFO] BUILD SUCCESS",
                                "SUCCESS",
                                Instant.now(),
                                true,
                                List.of(new TestReportItem("com.linkforge.service.link.CustomAliasValidationTest", "validatesCustomAlias", "PASSED", 120, null, List.of("AC-1")))
                        )
                );

        GovernedExecutionService testExecutionService = new GovernedExecutionService(
                proposerAgent,
                patchApplier,
                testValidator,
                workflowRepository,
                inspectionProperties,
                executionProperties
        );

        // 1. Submit workflow and propose implementation
        WorkflowRun run = new WorkflowRun("Add custom alias validation for short links");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        testExecutionService.proposeImplementation(run);

        // 2. Approve initial proposal
        WorkflowApproval planApproval = WorkflowApproval.of(run.getId(), "APPROVED", "lead-dev", run.getCurrentPlanHash(), "Approved initial plan");
        run.setApproval(planApproval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        // 3. Execute implementation: initial build fails, diagnosis occurs, corrective repair proposed, paused at REPAIR_APPROVAL
        GovernedExecutionRecord initialRecord = testExecutionService.executeImplementation(run.getId(), run.getCurrentPlanHash());
        assertThat(initialRecord.status()).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(initialRecord.stage()).isEqualTo("REPAIR_APPROVAL");

        WorkflowRun pausedRun = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(pausedRun.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);
        assertThat(pausedRun.getCurrentStage()).isEqualTo(WorkflowStage.REPAIR_APPROVAL);
        assertThat(pausedRun.getLastDiagnosis()).isNotNull();
        assertThat(pausedRun.getLastDiagnosis().repairable()).isTrue();
        assertThat(pausedRun.getLastDiagnosis().likelyCause()).contains("Compilation failure");
        assertThat(pausedRun.getRepairProposal()).isNotNull();
        assertThat(pausedRun.getRepairProposal().safe()).isTrue();
        assertThat(pausedRun.getRepairProposal().attemptNumber()).isEqualTo(1);
        assertThat(pausedRun.getCurrentPlanHash()).isEqualTo(pausedRun.getRepairProposal().repairHash());
        assertThat(pausedRun.getEvents()).anyMatch(e -> "BUILD_DIAGNOSED".equals(e.eventType()));
        assertThat(pausedRun.getEvents()).anyMatch(e -> "REPAIR_PROPOSED".equals(e.eventType()));

        // 4. Approve repair and execute repair in the same workspace
        String repairHash = pausedRun.getCurrentPlanHash();
        GovernedExecutionRecord completedRecord = testExecutionService.executeRepair(
                run.getId(),
                repairHash,
                "APPROVED",
                "lead-architect",
                "Approved corrective repair"
        );

        // 5. Verify final persisted outcome in database
        assertThat(completedRecord.status()).isEqualTo("COMPLETED");
        assertThat(completedRecord.stage()).isEqualTo("FINISHED");
        assertThat(completedRecord.buildValidation().isSuccess()).isTrue();

        WorkflowRun completedRun = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(completedRun.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completedRun.getCurrentStage()).isEqualTo(WorkflowStage.FINISHED);
        assertThat(completedRun.getRepairAttempts()).hasSize(1);
        assertThat(completedRun.getRepairAttempts().get(0).approvalDecision()).isEqualTo("APPROVED");
        assertThat(completedRun.getRepairAttempts().get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(completedRun.getEvents()).anyMatch(e -> "REPAIR_APPROVED".equals(e.eventType()));
        assertThat(completedRun.getEvents()).anyMatch(e -> "WORKFLOW_COMPLETED".equals(e.eventType()));
    }

    @Test
    @DisplayName("Requirement 4: End-to-end repair rejection triggers verified rollback and transitions workflow to ROLLED_BACK")
    void endToEndRepairWorkflowRollbackOnRejection() {
        GovernedBuildValidator testValidator = org.mockito.Mockito.mock(GovernedBuildValidator.class);
        org.mockito.Mockito.when(testValidator.validateBuild(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BuildValidationResult(
                        "./mvnw --batch-mode clean verify",
                        1,
                        1900,
                        "[ERROR] COMPILATION ERROR : /src/main/java/com/linkforge/service/link/AliasValidator.java:[10,5] ';' expected",
                        "BUILD_FAILED",
                        Instant.now(),
                        true,
                        List.of()
                ));

        GovernedExecutionService testExecutionService = new GovernedExecutionService(
                proposerAgent,
                patchApplier,
                testValidator,
                workflowRepository,
                inspectionProperties,
                executionProperties
        );

        WorkflowRun run = new WorkflowRun("Add custom alias validation for short links");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        testExecutionService.proposeImplementation(run);

        WorkflowApproval planApproval = WorkflowApproval.of(run.getId(), "APPROVED", "lead-dev", run.getCurrentPlanHash(), "Approved initial plan");
        run.setApproval(planApproval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        // Execute initial implementation: fails and pauses for repair approval
        testExecutionService.executeImplementation(run.getId(), run.getCurrentPlanHash());

        WorkflowRun pausedRun = workflowRepository.findById(run.getId()).orElseThrow();
        String repairHash = pausedRun.getCurrentPlanHash();

        // Reject repair
        GovernedExecutionRecord rolledBackRecord = testExecutionService.executeRepair(
                run.getId(),
                repairHash,
                "REJECTED",
                "security-reviewer",
                "Repair rejected by human reviewer"
        );

        assertThat(rolledBackRecord.status()).isEqualTo("ROLLED_BACK");
        assertThat(rolledBackRecord.stage()).isEqualTo("ROLLED_BACK");
        assertThat(rolledBackRecord.rollback()).isNotNull();
        assertThat(rolledBackRecord.rollback().success()).isTrue();

        WorkflowRun rolledBackRun = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(rolledBackRun.getStatus()).isEqualTo(WorkflowStatus.ROLLED_BACK);
        assertThat(rolledBackRun.getCurrentStage()).isEqualTo(WorkflowStage.ROLLED_BACK);
        assertThat(rolledBackRun.getRepairAttempts()).hasSize(1);
        assertThat(rolledBackRun.getRepairAttempts().get(0).status()).isEqualTo("REJECTED");
        assertThat(rolledBackRun.getEvents()).anyMatch(e -> "REPAIR_REJECTED".equals(e.eventType()));
        assertThat(rolledBackRun.getEvents()).anyMatch(e -> "ROLLBACK_VERIFIED".equals(e.eventType()));
        assertThat(rolledBackRun.getEvents()).anyMatch(e -> "WORKFLOW_ROLLED_BACK".equals(e.eventType()));
    }

    @Test
    @DisplayName("Requirement 4: End-to-end bounded repair attempts exhaustion triggers verified rollback")
    void endToEndRepairWorkflowRollbackOnExhaustion() {
        GovernedExecutionProperties boundedProps = new GovernedExecutionProperties();
        boundedProps.setMaxRepairAttempts(1); // 1 max attempt

        GovernedBuildValidator testValidator = org.mockito.Mockito.mock(GovernedBuildValidator.class);
        org.mockito.Mockito.when(testValidator.validateBuild(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        new BuildValidationResult(
                                "./mvnw --batch-mode clean verify",
                                1,
                                1800,
                                "[ERROR] COMPILATION ERROR : /src/main/java/com/linkforge/service/link/AliasValidator.java:[10,5] ';' expected",
                                "BUILD_FAILED",
                                Instant.now(),
                                true,
                                List.of()
                        ),
                        new BuildValidationResult(
                                "./mvnw --batch-mode clean verify",
                                1,
                                2000,
                                "[ERROR] COMPILATION ERROR : syntax error still present",
                                "BUILD_FAILED",
                                Instant.now(),
                                true,
                                List.of()
                        )
                );

        GovernedExecutionService testExecutionService = new GovernedExecutionService(
                proposerAgent,
                patchApplier,
                testValidator,
                workflowRepository,
                inspectionProperties,
                boundedProps
        );

        WorkflowRun run = new WorkflowRun("Add custom alias validation for short links");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        testExecutionService.proposeImplementation(run);

        WorkflowApproval planApproval = WorkflowApproval.of(run.getId(), "APPROVED", "lead-dev", run.getCurrentPlanHash(), "Approved initial plan");
        run.setApproval(planApproval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        // Initial build fails, pauses at REPAIR_APPROVAL (attempt 1 proposed)
        testExecutionService.executeImplementation(run.getId(), run.getCurrentPlanHash());

        WorkflowRun pausedRun = workflowRepository.findById(run.getId()).orElseThrow();
        String repairHash = pausedRun.getCurrentPlanHash();

        // Approve repair attempt 1 -> re-verification fails -> attempts exhausted (1 >= 1) -> verified rollback
        GovernedExecutionRecord exhaustedRecord = testExecutionService.executeRepair(
                run.getId(),
                repairHash,
                "APPROVED",
                "lead-dev",
                "Approved repair attempt 1"
        );

        assertThat(exhaustedRecord.status()).isEqualTo("ROLLED_BACK");
        assertThat(exhaustedRecord.stage()).isEqualTo("ROLLED_BACK");
        assertThat(exhaustedRecord.failureReason()).contains("Repair attempts exhausted");
        assertThat(exhaustedRecord.rollback()).isNotNull();
        assertThat(exhaustedRecord.rollback().success()).isTrue();

        WorkflowRun exhaustedRun = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(exhaustedRun.getStatus()).isEqualTo(WorkflowStatus.ROLLED_BACK);
        assertThat(exhaustedRun.getCurrentStage()).isEqualTo(WorkflowStage.ROLLED_BACK);
        assertThat(exhaustedRun.getRepairAttempts()).hasSize(1);
        assertThat(exhaustedRun.getRepairAttempts().get(0).status()).isEqualTo("FAILED");
        assertThat(exhaustedRun.getEvents()).anyMatch(e -> "ROLLBACK_VERIFIED".equals(e.eventType()));
        assertThat(exhaustedRun.getEvents()).anyMatch(e -> "WORKFLOW_ROLLED_BACK".equals(e.eventType()));
    }

    @Test
    @DisplayName("Criterion A: Real HTTP URL redirection issues HTTP 302 and records click analytics")
    void realHttpRedirectionAndAnalyticsTracking() throws Exception {
        // 1. Create short link via API
        String payload = """
                {
                  "destinationUrl": "https://spring.io/projects/spring-boot",
                  "customAlias": "sb-docs"
                }
                """;

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customAlias").value("sb-docs"))
                .andExpect(jsonPath("$.clickCount").value(0));

        // 2. Perform HTTP 302 redirect via short-url resolver
        mockMvc.perform(get("/r/sb-docs")
                        .header(HttpHeaders.REFERER, "https://developer.mozilla.org")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 TestBrowser"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://spring.io/projects/spring-boot"));

        // 3. Verify click analytics updated via real analytics endpoint
        mockMvc.perform(get("/api/v1/links/sb-docs/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customAlias").value("sb-docs"))
                .andExpect(jsonPath("$.clickCount").value(1))
                .andExpect(jsonPath("$.lastClickedAt", notNullValue()))
                .andExpect(jsonPath("$.recentClicks", hasSize(1)))
                .andExpect(jsonPath("$.recentClicks[0].referrer").value("https://developer.mozilla.org"))
                .andExpect(jsonPath("$.recentClicks[0].userAgent").value("Mozilla/5.0 TestBrowser"));
    }

    @Test
    @DisplayName("Criterion F: Workflow completion order enforces two-phase approval (plan then proposal)")
    void workflowLifecycleEnforcesTwoPhaseApprovalOrder() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener with Base62 tokens and click analytics"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        // Phase 1: Approve plan -> completes specialist coordination only, pauses for proposal approval
        MvcResult approvePlanResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andExpect(jsonPath("$.tasks", hasSize(5)))
                .andReturn();

        String proposalHash = objectMapper.readTree(approvePlanResult.getResponse().getContentAsString()).get("planHash").asText();
        assertThat(proposalHash).isNotEqualTo(planHash);

        // Phase 2: Approve proposal -> executes isolated build and transitions to COMPLETED
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", proposalHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"));
    }

    @Test
    @DisplayName("Requirement 3: Greenfield execution derives bounds (minimum 4) from requirement and verifies rejecting 3 and accepting 4")
    void greenfieldExecutionDerivesAliasBoundsAndEnforcesMin4Rejection() throws Exception {
        String reqPayload = """
                {
                  "requirement": "Build a greenfield standalone URL shortener from an empty source baseline with custom alias length between 4 and 25 characters"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        // Phase 1 approval: Plan -> Proposal
        MvcResult approvePlanResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andReturn();

        WorkflowRun run = workflowRepository.findById(id).orElseThrow();
        ImplementationProposal proposal = run.getImplementationProposal();
        assertThat(proposal).isNotNull();
        assertThat(proposal.supported()).isTrue();

        // Verify proposed StandaloneUrlShortener enforces MIN_LENGTH = 4 and MAX_LENGTH = 25 (not hardcoded 3-30)
        FileChangeProposal shortenerChange = proposal.changes().stream()
                .filter(c -> c.path().contains("StandaloneUrlShortener.java"))
                .findFirst().orElseThrow();
        assertThat(shortenerChange.proposedContent()).contains("MIN_LENGTH = 4;");
        assertThat(shortenerChange.proposedContent()).contains("MAX_LENGTH = 25;");
        assertThat(shortenerChange.proposedContent()).contains("{4,25}");

        // Phase 2 approval: Proposal -> Isolated execution
        String proposalHash = objectMapper.readTree(approvePlanResult.getResponse().getContentAsString()).get("planHash").asText();
        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", proposalHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"));

        WorkflowRun executedRun = workflowRepository.findById(id).orElseThrow();
        GovernedExecutionRecord record = executedRun.getExecutionRecord();
        assertThat(record).isNotNull();
        assertThat(record.buildValidation().isSuccess()).isTrue();

        List<TestReportItem> testReports = record.buildValidation().testReports();
        assertThat(testReports.stream().anyMatch(t -> t.testName().contains("rejectsShortAlias") && t.isPassed())).isTrue();
        assertThat(testReports.stream().anyMatch(t -> t.testName().contains("acceptsMinimumLengthAlias") && t.isPassed())).isTrue();
    }

    @Test
    @DisplayName("Requirement 4: Non-repairable build failure triggers direct verified rollback without pausing at repair approval")
    void endToEndNonRepairableFailureTriggersDirectRollback() {
        GovernedBuildValidator testValidator = org.mockito.Mockito.mock(GovernedBuildValidator.class);
        org.mockito.Mockito.when(testValidator.validateBuild(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BuildValidationResult(
                        "./mvnw --batch-mode clean verify",
                        1,
                        1900,
                        "[FATAL] Corrupted project environment or unrecoverable failure",
                        "BUILD_FAILED",
                        Instant.now(),
                        true,
                        List.of()
                ));

        BuildDiagnosisSpecialistAgent mockDiagnosisAgent = org.mockito.Mockito.mock(BuildDiagnosisSpecialistAgent.class);
        org.mockito.Mockito.when(mockDiagnosisAgent.diagnose(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BuildDiagnosisResult(
                        "diag-fatal",
                        "Fatal unrecoverable infrastructure failure",
                        List.of("src/main/java/com/linkforge/service/link/AliasValidator.java"),
                        List.of("AC-1"),
                        false,
                        "Trigger immediate safe rollback",
                        Instant.now()
                ));

        GovernedExecutionService testExecutionService = new GovernedExecutionService(
                proposerAgent,
                patchApplier,
                testValidator,
                workflowRepository,
                inspectionProperties,
                executionProperties,
                mockDiagnosisAgent,
                null,
                null,
                null
        );

        WorkflowRun run = new WorkflowRun("Add custom alias validation for short links");
        run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
        testExecutionService.proposeImplementation(run);

        WorkflowApproval planApproval = WorkflowApproval.of(run.getId(), "APPROVED", "lead-dev", run.getCurrentPlanHash(), "Approved initial plan");
        run.setApproval(planApproval);
        run.transitionTo(WorkflowStatus.APPROVED, WorkflowStage.PLAN_APPROVAL);
        workflowRepository.save(run);

        GovernedExecutionRecord record = testExecutionService.executeImplementation(run.getId(), run.getCurrentPlanHash());

        assertThat(record.status()).isEqualTo("ROLLED_BACK");
        assertThat(record.stage()).isEqualTo("ROLLED_BACK");
        assertThat(record.rollback()).isNotNull();
        assertThat(record.rollback().success()).isTrue();

        WorkflowRun rolledBackRun = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(rolledBackRun.getStatus()).isEqualTo(WorkflowStatus.ROLLED_BACK);
        assertThat(rolledBackRun.getCurrentStage()).isEqualTo(WorkflowStage.ROLLED_BACK);
        assertThat(rolledBackRun.getLastDiagnosis()).isNotNull();
        assertThat(rolledBackRun.getLastDiagnosis().repairable()).isFalse();
        assertThat(rolledBackRun.getEvents()).anyMatch(e -> "BUILD_DIAGNOSED".equals(e.eventType()));
        assertThat(rolledBackRun.getEvents()).anyMatch(e -> "REPAIR_UNSAFE".equals(e.eventType()));
        assertThat(rolledBackRun.getEvents()).anyMatch(e -> "ROLLBACK_VERIFIED".equals(e.eventType()));
        assertThat(rolledBackRun.getEvents()).anyMatch(e -> "WORKFLOW_ROLLED_BACK".equals(e.eventType()));
    }
}
