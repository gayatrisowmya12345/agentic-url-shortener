package com.linkforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.agent.DependencyAwarePlannerAgent;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.agent.ScenarioClassifierAgent;
import com.linkforge.api.dto.CriterionEvidenceItem;
import com.linkforge.api.dto.ExecutionVerificationStatus;
import com.linkforge.api.dto.TaskTraceabilityItem;
import com.linkforge.api.dto.WorkflowEvidenceResponse;
import com.linkforge.api.dto.WorkflowSummaryResponse;
import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowCancellation;
import com.linkforge.domain.workflow.WorkflowClarification;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.FileChangeOperation;
import com.linkforge.domain.workflow.implementation.FileChangeProposal;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.TestReportItem;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;
import com.linkforge.service.evidence.WorkflowEvidenceService;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.inspection.CodebaseInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowEvidenceAndObservabilityServiceTest {

    private WorkflowOrchestrator orchestrator;
    private WorkflowRepository repository;
    private WorkflowEvidenceService evidenceService;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new WorkflowRepository(jdbcTemplate, new ObjectMapper());
        repository.clear();

        evidenceService = new WorkflowEvidenceService();
        orchestrator = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                new RequirementInterpreterAgent(),
                new DependencyAwarePlannerAgent(),
                repository
        );
    }

    @Test
    @DisplayName("Persisted history maintains chronological event and decision ordering")
    void persistedHistoryMaintainsChronologicalOrdering() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);

        List<WorkflowEvent> events = repository.findEventsByWorkflowId(run.getId());
        assertThat(events).isNotEmpty();

        // Verify timestamps are non-decreasing
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).timestamp()).isAfterOrEqualTo(events.get(i - 1).timestamp());
        }

        List<AgentDecision> decisions = repository.findAgentDecisionsByWorkflowId(run.getId());
        assertThat(decisions).hasSize(3);
        assertThat(decisions.get(0).agentName()).isEqualTo("scenario-classifier");
        assertThat(decisions.get(1).agentName()).isEqualTo("requirement-interpreter");
        assertThat(decisions.get(2).agentName()).isEqualTo("deterministic-dependency-planner");
    }

    @Test
    @DisplayName("Evidence view correctly links acceptance criteria to tasks, specialists, and events")
    void evidenceViewLinksCriteriaToTasksAndSpecialists() {
        WorkflowRun run = orchestrator.startWorkflow("Build a greenfield URL shortener with Base62 tokens and click analytics");
        run = orchestrator.approvePlan(run.getId(), "APPROVED", run.getCurrentPlanHash(), "lead-architect", "Plan verified").orElseThrow();
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        assertThat(evidence.workflowId()).isEqualTo(run.getId());
        assertThat(evidence.scenario()).isEqualTo("GREENFIELD");
        assertThat(evidence.status()).isEqualTo("COMPLETED");
        assertThat(evidence.planApproved()).isTrue();

        // Verify acceptance criteria linkage
        List<CriterionEvidenceItem> criteria = evidence.criteriaEvidence();
        assertThat(criteria).isNotEmpty();
        assertThat(criteria.stream().anyMatch(c -> "ANALYZED".equals(c.status()))).isTrue();
        for (CriterionEvidenceItem item : criteria) {
            assertThat(item.criterionId()).startsWith("AC-");
            assertThat(item.status()).isIn("ANALYZED", "PLANNED", "UNCOVERED");
        }

        // Verify task traceability
        List<TaskTraceabilityItem> tasks = evidence.taskTraceability();
        assertThat(tasks).hasSize(run.getTasks().size());
        for (TaskTraceabilityItem task : tasks) {
            assertThat(task.taskId()).startsWith("TASK-");
            assertThat(task.status()).isEqualTo("COMPLETED");
            assertThat(task.specialistExecuted()).isTrue();
            assertThat(task.specialistAgentName()).isNotNull();
        }
    }

    @Test
    @DisplayName("Evidence view honestly marks unverified and unsupported capabilities")
    void evidenceViewHonestlyMarksUnverifiedCapabilities() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);

        ExecutionVerificationStatus status = evidence.verificationStatus();
        assertThat(status.requirementAnalysis()).isEqualTo("COMPLETED");
        assertThat(status.scenarioClassification()).isEqualTo("GREENFIELD");
        assertThat(status.codebaseInspection()).isEqualTo("NOT_APPLICABLE (GREENFIELD)");
        assertThat(status.taskPlanning()).isEqualTo("COMPLETED");
        assertThat(status.humanApprovalGate()).isEqualTo("AWAITING_APPROVAL");

        // Explicitly unexecuted / unsupported capabilities
        assertThat(status.sourceCodeGeneration()).isEqualTo("NOT_SUPPORTED");
        assertThat(status.buildExecution()).isEqualTo("NOT_SUPPORTED");
        assertThat(status.automatedTestExecution()).isEqualTo("UNVERIFIED");
        assertThat(status.deploymentAndRelease()).isEqualTo("NOT_SUPPORTED");
    }

    @Test
    @DisplayName("Evidence view reports missing evidence for unapproved or paused workflows")
    void evidenceViewReportsMissingEvidenceForPausedWorkflows() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        assertThat(evidence.status()).isEqualTo("WAITING_FOR_CLARIFICATION");
        assertThat(evidence.planApproved()).isFalse();
        assertThat(evidence.criteriaEvidence()).isEmpty();
        assertThat(evidence.taskTraceability()).isEmpty();
        assertThat(evidence.verificationStatus().taskPlanning()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Workflow summary derives metrics strictly from persisted records")
    void workflowSummaryDerivesMetricsStrictlyFromRecords() {
        WorkflowRun run = orchestrator.startWorkflow("Build greenfield URL shortener with Base62 tokens and click metrics");
        WorkflowSummaryResponse summary = WorkflowSummaryResponse.from(run);

        assertThat(summary.workflowId()).isEqualTo(run.getId());
        assertThat(summary.status()).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(summary.requirementRevision()).isEqualTo(1);
        assertThat(summary.scenario()).isEqualTo("GREENFIELD");
        assertThat(summary.taskCount()).isEqualTo(run.getTasks().size());
        assertThat(summary.completedTaskCount()).isEqualTo(0);
        assertThat(summary.planApproved()).isFalse();
        assertThat(summary.completeness().unverifiedCapabilities()).contains(
                "SOURCE_CODE_GENERATION", "BUILD_EXECUTION", "AUTOMATED_TEST_RUNS", "DEPLOYMENT"
        );

        // Add clarification to test revision increment
        WorkflowRun ambiguous = orchestrator.startWorkflow("make links faster");
        ambiguous = orchestrator.submitClarification(ambiguous.getId(), "Build greenfield shortener", null, "operator").orElseThrow();
        WorkflowSummaryResponse clarifiedSummary = WorkflowSummaryResponse.from(ambiguous);
        assertThat(clarifiedSummary.requirementRevision()).isEqualTo(2);
        assertThat(clarifiedSummary.clarificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Restart-safe persistence rehydrates workflow completely from database")
    void restartSafePersistenceRehydratesWorkflowFromDatabase() {
        WorkflowRun run = orchestrator.startWorkflow("Build a greenfield URL shortener with Base62 tokens and analytics");
        run = orchestrator.approvePlan(run.getId(), "APPROVED", run.getCurrentPlanHash(), "lead-architect", "Approved").orElseThrow();
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

        String id = run.getId();
        int eventCount = run.getEvents().size();
        int taskCount = run.getTasks().size();
        int invCount = run.getSpecialistInvocations().size();

        // Wipe memory cache to simulate full JVM restart
        repository.clearMemoryCache();

        // Re-read from repository (hydrates directly from H2)
        Optional<WorkflowRun> rehydratedOpt = repository.findById(id);
        assertThat(rehydratedOpt).isPresent();

        WorkflowRun rehydrated = rehydratedOpt.get();
        assertThat(rehydrated.getId()).isEqualTo(id);
        assertThat(rehydrated.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(rehydrated.getCurrentStage()).isEqualTo(WorkflowStage.FINISHED);
        assertThat(rehydrated.getScenario()).isEqualTo(Scenario.GREENFIELD);
        assertThat(rehydrated.getTasks()).hasSize(taskCount);
        assertThat(rehydrated.getSpecialistInvocations()).hasSize(invCount);
        assertThat(rehydrated.getEvents()).hasSize(eventCount);
        assertThat(rehydrated.getApproval()).isNotNull();
        assertThat(rehydrated.getApproval().approver()).isEqualTo("lead-architect");

        // Verify evidence can be generated from rehydrated run
        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(rehydrated);
        assertThat(evidence.status()).isEqualTo("COMPLETED");
        assertThat(evidence.criteriaEvidence()).isNotEmpty();
    }

    @Test
    @DisplayName("Secrets and tokens are not stored in audit history or decision details")
    void secretsNotStoredInAuditHistory() {
        WorkflowRun run = orchestrator.startWorkflow("Build URL shortener with tokens");
        run = orchestrator.cancelWorkflow(run.getId(), "sec-admin", "Terminated").orElseThrow();

        List<WorkflowEvent> events = repository.findEventsByWorkflowId(run.getId());
        for (WorkflowEvent event : events) {
            assertThat(event.description()).doesNotContain("dev-approval-token");
            assertThat(event.description()).doesNotContain("dev-clarification-token");
            assertThat(event.description()).doesNotContain("dev-cancellation-token");
            assertThat(event.description()).doesNotContain("dev-operator-token");
        }

        List<AgentDecision> decisions = repository.findAgentDecisionsByWorkflowId(run.getId());
        for (AgentDecision decision : decisions) {
            assertThat(decision.rationale()).doesNotContain("dev-operator-token");
        }
    }

    @Test
    @DisplayName("Generic workflow events are not attached to criteria, and event linkage is reported as unavailable")
    void genericWorkflowEventsNotAttachedToCriteria() {
        WorkflowRun run = orchestrator.startWorkflow("Build a greenfield URL shortener with Base62 tokens and click analytics");
        run = orchestrator.approvePlan(run.getId(), "APPROVED", run.getCurrentPlanHash(), "lead-architect", "Plan verified").orElseThrow();
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

        // Verify that pipeline events exist
        assertThat(run.getEvents()).isNotEmpty();

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        assertThat(evidence.eventLinkageStatus()).isEqualTo("EVENT_LEVEL_LINKAGE_UNAVAILABLE");

        // None of the criteria should have generic pipeline events attached
        for (CriterionEvidenceItem item : evidence.criteriaEvidence()) {
            assertThat(item.relevantEventTypes())
                    .as("Criterion %s should not have generic pipeline events attached", item.criterionId())
                    .isEmpty();
            assertThat(item.eventLinkageStatus()).isEqualTo("EVENT_LEVEL_LINKAGE_UNAVAILABLE");
        }
    }

    @Test
    @DisplayName("Coverage counts reflect actual persisted links without min(taskCount, criterionCount) estimation")
    void coverageCountsReflectActualPersistedLinksWithoutEstimation() {
        WorkflowRun run = orchestrator.startWorkflow("Build greenfield URL shortener with Base62 tokens and click metrics");
        // In WAITING_FOR_APPROVAL state, no specialist invocations have run yet
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);

        WorkflowSummaryResponse summary = WorkflowSummaryResponse.from(run);
        // Before execution, planned criteria should NOT be estimated as min(5, 5) = 5
        assertThat(summary.completeness().criteriaPlannedInTasks()).isEqualTo(0);
        assertThat(summary.completeness().criteriaAddressedBySpecialists()).isEqualTo(0);
        assertThat(summary.completeness().coveragePercentage()).isEqualTo(0.0);

        // Evidence before approval: all criteria are UNCOVERED
        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        for (CriterionEvidenceItem item : evidence.criteriaEvidence()) {
            assertThat(item.status()).isEqualTo("UNCOVERED");
            assertThat(item.hasPersistedEvidence()).isFalse();
            assertThat(item.specialistFindings()).isEmpty();
        }

        // Now approve and coordinate
        WorkflowRun completed = orchestrator.approvePlan(run.getId(), "APPROVED", run.getCurrentPlanHash(), "architect", "Approved").orElseThrow();
        WorkflowSummaryResponse completedSummary = WorkflowSummaryResponse.from(completed);

        // After coordination, count only actual addressed criteria
        int addressed = completedSummary.completeness().criteriaAddressedBySpecialists();
        assertThat(addressed).isGreaterThan(0);
        assertThat(completedSummary.completeness().criteriaPlannedInTasks()).isEqualTo(addressed);
        assertThat(completedSummary.completeness().coveragePercentage()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Evidence response excludes repositoryPath and protects absolute filesystem paths")
    void evidenceResponseExcludesRepositoryPathAndLocalPaths() throws Exception {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);

        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        String json = om.writeValueAsString(evidence);

        // Verify repositoryPath field is completely absent from operator evidence response
        assertThat(json).doesNotContain("\"repositoryPath\"");

        // Verify no local filesystem paths or secrets leak into serialized evidence
        assertThat(json).doesNotContain("/Users/");
        assertThat(json).doesNotContain("/home/");
        assertThat(json).doesNotContain("/var/");
        assertThat(json).doesNotContain("dev-operator-token");
        assertThat(json).doesNotContain("dev-approval-token");
        assertThat(json).doesNotContain("dev-cancellation-token");
    }

    @Test
    @DisplayName("Evidence view reports targeted test execution as verified when governed test command succeeds with proven test records")
    void evidenceViewReportsTargetedTestExecutionVerifiedWhenBuildSucceeds() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        BuildValidationResult buildResult = new BuildValidationResult(
                "./mvnw --batch-mode test -Dtest=CustomAliasValidationTest",
                0,
                3200,
                "[INFO] BUILD SUCCESS",
                "SUCCESS",
                Instant.now(),
                false,
                List.of(new com.linkforge.domain.workflow.implementation.TestReportItem(
                        "com.linkforge.api.CustomAliasValidationTest",
                        "validatesCustomAlias",
                        "PASSED",
                        150,
                        null,
                        List.of("AC-ALIAS-1")
                ))
        );
        ImplementationProposal proposal = ImplementationProposal.supported(
                "ALIAS_VALIDATION",
                List.of(FileChangeProposal.of("src/test/java/com/linkforge/api/CustomAliasValidationTest.java",
                        FileChangeOperation.CREATE, "// test", null, "TASK-1", "AC-ALIAS-1", "TESTING", "Test"))
        );
        run.setImplementationProposal(proposal);
        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-123",
                run.getId(),
                "COMPLETED",
                "FINISHED",
                proposal.proposalHash(),
                proposal,
                List.of(),
                buildResult,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
        run.setExecutionRecord(record);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        ExecutionVerificationStatus status = evidence.verificationStatus();

        assertThat(status.sourceCodeGeneration()).isEqualTo("VERIFIED (ISOLATED_PROPOSAL)");
        assertThat(status.buildExecution()).isEqualTo("VERIFIED (MAVEN_WRAPPER_BUILD)");
        assertThat(status.automatedTestExecution()).isEqualTo("VERIFIED (TARGETED_TEST_EXECUTION)");
        assertThat(status.deploymentAndRelease()).isEqualTo("NOT_SUPPORTED");
    }

    @Test
    @DisplayName("Evidence view reports full verification when clean verify discovers passing tests covering all proposal criteria")
    void evidenceViewReportsFullVerificationWhenAllCriteriaCovered() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        ImplementationProposal proposal = ImplementationProposal.supported(
                "URL_SHORTENER_CORE",
                List.of(
                        FileChangeProposal.of("src/main/java/LinkShortener.java", FileChangeOperation.CREATE, "// code", null, "TASK-1", "AC-1", "API", "Service"),
                        FileChangeProposal.of("src/main/java/AliasValidator.java", FileChangeOperation.CREATE, "// code", null, "TASK-2", "AC-2", "API", "Validator")
                )
        );
        run.setImplementationProposal(proposal);

        BuildValidationResult buildResult = new BuildValidationResult(
                "./mvnw --batch-mode clean verify",
                0,
                5400,
                "[INFO] BUILD SUCCESS",
                "SUCCESS",
                Instant.now(),
                true,
                List.of(
                        new TestReportItem("com.linkforge.LinkShortenerTest", "createsShortLink", "PASSED", 200, null, List.of("AC-1")),
                        new TestReportItem("com.linkforge.AliasValidatorTest", "validatesAlias", "PASSED", 150, null, List.of("AC-2"))
                )
        );

        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-full", run.getId(), "COMPLETED", "FINISHED", proposal.proposalHash(), proposal, List.of(),
                buildResult, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        ExecutionVerificationStatus status = evidence.verificationStatus();

        assertThat(status.automatedTestExecution()).isEqualTo("VERIFIED (FULL_VERIFICATION)");
        assertThat(evidence.criteriaEvidence().stream()
                .filter(c -> "AC-1".equals(c.criterionId()) || "AC-2".equals(c.criterionId()))
                .allMatch(c -> "PASSED".equals(c.validationStatus()))).isTrue();
    }

    @Test
    @DisplayName("Evidence view reports missing criterion coverage when proposal criteria are only partially covered by passing tests")
    void evidenceViewReportsMissingCoverageWhenCriteriaPartiallyCovered() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        ImplementationProposal proposal = ImplementationProposal.supported(
                "URL_SHORTENER_CORE",
                List.of(
                        FileChangeProposal.of("src/main/java/LinkShortener.java", FileChangeOperation.CREATE, "// code", null, "TASK-1", "AC-1", "API", "Service"),
                        FileChangeProposal.of("src/main/java/AliasValidator.java", FileChangeOperation.CREATE, "// code", null, "TASK-2", "AC-2", "API", "Validator")
                )
        );
        run.setImplementationProposal(proposal);

        // Only AC-1 has a passing test; AC-2 has no test
        BuildValidationResult buildResult = new BuildValidationResult(
                "./mvnw --batch-mode clean verify",
                0,
                4200,
                "[INFO] BUILD SUCCESS",
                "SUCCESS",
                Instant.now(),
                true,
                List.of(
                        new TestReportItem("com.linkforge.LinkShortenerTest", "createsShortLink", "PASSED", 200, null, List.of("AC-1"))
                )
        );

        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-partial", run.getId(), "COMPLETED", "FINISHED", proposal.proposalHash(), proposal, List.of(),
                buildResult, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        ExecutionVerificationStatus status = evidence.verificationStatus();

        assertThat(status.automatedTestExecution()).isEqualTo("UNVERIFIED (MISSING_CRITERION_COVERAGE)");
    }

    @Test
    @DisplayName("Evidence view reports tests failed when surefire reports contain test failure or error")
    void evidenceViewReportsTestsFailedWhenTestReportsContainFailure() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        ImplementationProposal proposal = ImplementationProposal.supported(
                "ALIAS_VALIDATION",
                List.of(
                        FileChangeProposal.of("src/main/java/AliasValidator.java", FileChangeOperation.CREATE, "// code", null, "TASK-1", "AC-1", "API", "Validator")
                )
        );
        run.setImplementationProposal(proposal);

        BuildValidationResult buildResult = new BuildValidationResult(
                "./mvnw --batch-mode clean verify",
                1,
                3100,
                "[INFO] BUILD FAILURE - Tests run: 1, Failures: 1",
                "BUILD_FAILED",
                Instant.now(),
                true,
                List.of(
                        new TestReportItem("com.linkforge.AliasValidatorTest", "validatesAlias", "FAILED", 120, "Assertion failed: expected 4 but was 3", List.of("AC-1"))
                )
        );

        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-fail", run.getId(), "COMPLETED", "FINISHED", proposal.proposalHash(), proposal, List.of(),
                buildResult, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        ExecutionVerificationStatus status = evidence.verificationStatus();

        assertThat(status.automatedTestExecution()).isEqualTo("UNVERIFIED (TESTS_FAILED)");
    }

    @Test
    @DisplayName("Evidence view reports zero relevant tests when tests exist but none cover proposal criteria")
    void evidenceViewReportsZeroRelevantTestsWhenTestsDoNotCoverCriteria() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        ImplementationProposal proposal = ImplementationProposal.supported(
                "ALIAS_VALIDATION",
                List.of(
                        FileChangeProposal.of("src/main/java/AliasValidator.java", FileChangeOperation.CREATE, "// code", null, "TASK-1", "AC-ALIAS-1", "API", "Validator")
                )
        );
        run.setImplementationProposal(proposal);

        // Test runs and passes, but is for an unrelated criterion (AC-UNRELATED)
        BuildValidationResult buildResult = new BuildValidationResult(
                "./mvnw --batch-mode clean verify",
                0,
                2900,
                "[INFO] BUILD SUCCESS",
                "SUCCESS",
                Instant.now(),
                true,
                List.of(
                        new TestReportItem("com.linkforge.UnrelatedTest", "testSomethingElse", "PASSED", 100, null, List.of("AC-UNRELATED"))
                )
        );

        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-unrelated", run.getId(), "COMPLETED", "FINISHED", proposal.proposalHash(), proposal, List.of(),
                buildResult, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        ExecutionVerificationStatus status = evidence.verificationStatus();

        assertThat(status.automatedTestExecution()).isEqualTo("UNVERIFIED (ZERO_RELEVANT_TESTS)");
    }

    @Test
    @DisplayName("Evidence view reports automated test execution as unverified when build has zero relevant tests")
    void evidenceViewReportsUnverifiedWhenZeroRelevantTests() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        BuildValidationResult buildResult = new BuildValidationResult(
                "./mvnw --batch-mode test",
                0,
                1500,
                "[INFO] BUILD SUCCESS",
                "SUCCESS",
                Instant.now(),
                false,
                List.of()
        );
        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-zero",
                run.getId(),
                "COMPLETED",
                "FINISHED",
                "hash-zero",
                null,
                List.of(),
                buildResult,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
        run.setExecutionRecord(record);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        ExecutionVerificationStatus status = evidence.verificationStatus();

        assertThat(status.automatedTestExecution()).isEqualTo("UNVERIFIED (ZERO_RELEVANT_TESTS)");
    }

    @Test
    @DisplayName("Evidence view accurately distinguishes non-success execution cases: blocked, timed-out, rolled-back, failed, and plan-only")
    void evidenceViewAccuratelyDistinguishesNonSuccessCases() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");

        // 1. Plan-only (no execution record)
        run.setExecutionRecord(null);
        ExecutionVerificationStatus planOnly = evidenceService.buildEvidenceResponse(run).verificationStatus();
        assertThat(planOnly.sourceCodeGeneration()).isEqualTo("NOT_SUPPORTED");
        assertThat(planOnly.buildExecution()).isEqualTo("NOT_SUPPORTED");
        assertThat(planOnly.automatedTestExecution()).isEqualTo("UNVERIFIED");

        // 2. Blocked
        run.setExecutionRecord(new GovernedExecutionRecord(
                "exec-blocked", run.getId(), "BLOCKED", "INITIALIZED", "hash", null, List.of(), null, null, "Untrusted repo", Instant.now(), Instant.now()
        ));
        ExecutionVerificationStatus blocked = evidenceService.buildEvidenceResponse(run).verificationStatus();
        assertThat(blocked.sourceCodeGeneration()).isEqualTo("BLOCKED");
        assertThat(blocked.buildExecution()).isEqualTo("BLOCKED");
        assertThat(blocked.automatedTestExecution()).isEqualTo("BLOCKED");

        // 3. Timed out
        run.setExecutionRecord(new GovernedExecutionRecord(
                "exec-timeout", run.getId(), "TIMED_OUT", "VALIDATING", "hash", null, List.of(),
                new BuildValidationResult("./mvnw test", -1, 60000, "Timeout", "TIMED_OUT", Instant.now()),
                null, "Timed out after 60s", Instant.now(), Instant.now()
        ));
        ExecutionVerificationStatus timedOut = evidenceService.buildEvidenceResponse(run).verificationStatus();
        assertThat(timedOut.sourceCodeGeneration()).isEqualTo("FAILED (TIMED_OUT)");
        assertThat(timedOut.buildExecution()).isEqualTo("TIMED_OUT");
        assertThat(timedOut.automatedTestExecution()).isEqualTo("FAILED (TIMED_OUT)");

        // 4. Rolled back
        run.setExecutionRecord(new GovernedExecutionRecord(
                "exec-rollback", run.getId(), "ROLLED_BACK", "ROLLING_BACK", "hash", null, List.of(),
                new BuildValidationResult("./mvnw test", 1, 1200, "Build failure", "BUILD_FAILED", Instant.now()),
                null, "Compilation failed", Instant.now(), Instant.now()
        ));
        ExecutionVerificationStatus rolledBack = evidenceService.buildEvidenceResponse(run).verificationStatus();
        assertThat(rolledBack.sourceCodeGeneration()).isEqualTo("ROLLED_BACK (VERIFIED_RESTORATION)");
        assertThat(rolledBack.buildExecution()).isEqualTo("FAILED (ROLLED_BACK)");
        assertThat(rolledBack.automatedTestExecution()).isEqualTo("FAILED (ROLLED_BACK)");

        // 5. Failed
        run.setExecutionRecord(new GovernedExecutionRecord(
                "exec-failed", run.getId(), "FAILED", "VALIDATING", "hash", null, List.of(),
                new BuildValidationResult("./mvnw test", 1, 1500, "Error", "FAILED", Instant.now()),
                null, "Execution failure", Instant.now(), Instant.now()
        ));
        ExecutionVerificationStatus failed = evidenceService.buildEvidenceResponse(run).verificationStatus();
        assertThat(failed.sourceCodeGeneration()).isEqualTo("FAILED");
        assertThat(failed.buildExecution()).isEqualTo("FAILED");
        assertThat(failed.automatedTestExecution()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Criterion matching requires exact ID after normalization: AC-10 cannot satisfy AC-1")
    void criterionMatchingRequiresExactIdAndAc10CannotSatisfyAc1() {
        TestReportItem ac10Test = new TestReportItem(
                "com.linkforge.api.FeatureTenTest", "testFeatureTen", "PASSED", 100, null, List.of("AC-10")
        );
        TestReportItem ac1Test = new TestReportItem(
                "com.linkforge.api.FeatureOneTest", "testFeatureOne", "PASSED", 100, null, List.of("AC-1")
        );
        TestReportItem multiTest = new TestReportItem(
                "com.linkforge.api.MultiTest", "testMulti", "PASSED", 100, null, List.of("AC-10; AC-2")
        );

        // AC-10 test must NEVER satisfy AC-1
        assertThat(WorkflowEvidenceService.testCoversCriterion(ac10Test, "AC-1"))
                .as("AC-10 must not satisfy AC-1 via substring matching")
                .isFalse();

        // Exact matches with case/whitespace normalization must satisfy
        assertThat(WorkflowEvidenceService.testCoversCriterion(ac1Test, "AC-1")).isTrue();
        assertThat(WorkflowEvidenceService.testCoversCriterion(ac1Test, "ac-1")).isTrue();
        assertThat(WorkflowEvidenceService.testCoversCriterion(ac1Test, "  AC-1  ")).isTrue();

        // Substring contained in multiple delimited tokens
        assertThat(WorkflowEvidenceService.testCoversCriterion(multiTest, "AC-1"))
                .as("Token AC-10 in delimited list must not satisfy AC-1")
                .isFalse();
        assertThat(WorkflowEvidenceService.testCoversCriterion(multiTest, "AC-2")).isTrue();
        assertThat(WorkflowEvidenceService.testCoversCriterion(multiTest, "AC-10")).isTrue();
    }

    @Test
    @DisplayName("Evidence view leaves missing criterion coverage unverified when AC-10 cannot satisfy AC-1")
    void evidenceViewLeavesMissingCoverageUnverifiedWhenAc10CannotSatisfyAc1() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        ImplementationProposal proposal = ImplementationProposal.supported(
                "URL_SHORTENER_CORE",
                List.of(
                        FileChangeProposal.of("src/main/java/LinkShortener.java", FileChangeOperation.CREATE, "// code", null, "TASK-1", "AC-1", "API", "Service"),
                        FileChangeProposal.of("src/main/java/AliasValidator.java", FileChangeOperation.CREATE, "// code", null, "TASK-2", "AC-2", "API", "Validator")
                )
        );
        run.setImplementationProposal(proposal);

        // Passing test exists for AC-10 (not AC-1) and AC-2
        BuildValidationResult buildResult = new BuildValidationResult(
                "./mvnw --batch-mode clean verify",
                0,
                4200,
                "[INFO] BUILD SUCCESS",
                "SUCCESS",
                Instant.now(),
                true,
                List.of(
                        new TestReportItem("com.linkforge.FeatureTenTest", "testTen", "PASSED", 200, null, List.of("AC-10")),
                        new TestReportItem("com.linkforge.AliasValidatorTest", "validatesAlias", "PASSED", 150, null, List.of("AC-2"))
                )
        );

        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-ac10-partial", run.getId(), "COMPLETED", "FINISHED", proposal.proposalHash(), proposal, List.of(),
                buildResult, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        ExecutionVerificationStatus status = evidence.verificationStatus();

        // Must report MISSING_CRITERION_COVERAGE because AC-1 is unsatisfied by AC-10
        assertThat(status.automatedTestExecution()).isEqualTo("UNVERIFIED (MISSING_CRITERION_COVERAGE)");

        // Individual criterion validation statuses: AC-1 remains UNVERIFIED, AC-2 is PASSED
        assertThat(evidence.criteriaEvidence().stream()
                .filter(c -> "AC-1".equals(c.criterionId()))
                .findFirst()
                .orElseThrow()
                .validationStatus()).isEqualTo("UNVERIFIED");

        assertThat(evidence.criteriaEvidence().stream()
                .filter(c -> "AC-2".equals(c.criterionId()))
                .findFirst()
                .orElseThrow()
                .validationStatus()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("Evidence view reports zero relevant tests when only AC-10 tests execute for AC-1 proposal")
    void evidenceViewReportsZeroRelevantTestsWhenOnlyAc10TestsExecuteForAc1Proposal() {
        WorkflowRun run = orchestrator.startWorkflow("Create a standard URL shortening service");
        ImplementationProposal proposal = ImplementationProposal.supported(
                "URL_SHORTENER_CORE",
                List.of(
                        FileChangeProposal.of("src/main/java/LinkShortener.java", FileChangeOperation.CREATE, "// code", null, "TASK-1", "AC-1", "API", "Service")
                )
        );
        run.setImplementationProposal(proposal);

        // Only AC-10 test ran
        BuildValidationResult buildResult = new BuildValidationResult(
                "./mvnw --batch-mode clean verify",
                0,
                3000,
                "[INFO] BUILD SUCCESS",
                "SUCCESS",
                Instant.now(),
                true,
                List.of(
                        new TestReportItem("com.linkforge.FeatureTenTest", "testTen", "PASSED", 200, null, List.of("AC-10"))
                )
        );

        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-ac10-zero", run.getId(), "COMPLETED", "FINISHED", proposal.proposalHash(), proposal, List.of(),
                buildResult, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);
        ExecutionVerificationStatus status = evidence.verificationStatus();

        // Must report ZERO_RELEVANT_TESTS because AC-10 does not cover AC-1
        assertThat(status.automatedTestExecution()).isEqualTo("UNVERIFIED (ZERO_RELEVANT_TESTS)");
        assertThat(evidence.criteriaEvidence().stream()
                .filter(c -> "AC-1".equals(c.criterionId()))
                .findFirst()
                .orElseThrow()
                .validationStatus()).isEqualTo("UNVERIFIED");
    }

    @Test
    @DisplayName("Regression: AC-10 tasks and events do not appear as AC-1 evidence, while exact AC-1 matches still work")
    void ac10TasksAndEventsDoNotAppearAsAc1EvidenceWhileExactAc1MatchesWork() {
        WorkflowRun run = new WorkflowRun("Add custom alias and token expiry features");
        run.setAcceptanceCriteria(List.of(
                "AC-1: Enforce custom alias constraints",
                "AC-10: Enforce token expiration policies"
        ));

        // Add tasks mentioning AC-10 and AC-1
        PlannedTask task1 = new PlannedTask("TASK-1", "Implement AC-1 alias constraint", "Validates alias bounds for AC-1", List.of(), "COMPLETED");
        PlannedTask task10 = new PlannedTask("TASK-10", "Implement AC-10 token expiry", "Handles token expiration for AC-10", List.of(), "COMPLETED");
        run.setTasks(List.of(task1, task10));

        // Add events mentioning AC-10 and AC-1
        WorkflowEvent event1 = WorkflowEvent.of("TASK_AC-1_COMPLETED", "STAGE_1", "Completed task TASK-1 for AC-1 successfully.");
        WorkflowEvent event10 = WorkflowEvent.of("TASK_AC-10_COMPLETED", "STAGE_10", "Completed task TASK-10 for AC-10 successfully.");
        run.addEvent(event1);
        run.addEvent(event10);

        // Also add specialist invocations
        SpecialistInvocation inv1 = new SpecialistInvocation("inv-1", "TASK-1", "agent-1", "SECURITY_VALIDATION", "SUCCESS", "input", "output", List.of("rec"), List.of("test"), List.of("AC-1"), "ollama", "m", false, null, Instant.now(), Instant.now());
        SpecialistInvocation inv10 = new SpecialistInvocation("inv-10", "TASK-10", "agent-10", "DATA_PERSISTENCE", "SUCCESS", "input", "output", List.of("rec"), List.of("test"), List.of("AC-10"), "ollama", "m", false, null, Instant.now(), Instant.now());
        run.setSpecialistInvocations(List.of(inv1, inv10));

        WorkflowEvidenceResponse evidence = evidenceService.buildEvidenceResponse(run);

        // Verify AC-1 evidence
        CriterionEvidenceItem ac1Item = evidence.criteriaEvidence().stream()
                .filter(c -> "AC-1".equals(c.criterionId()))
                .findFirst()
                .orElseThrow();

        assertThat(ac1Item.plannedTaskIds())
                .as("AC-1 must include TASK-1")
                .contains("TASK-1")
                .as("AC-1 must NOT include TASK-10 via substring matching")
                .doesNotContain("TASK-10");

        assertThat(ac1Item.relevantEventTypes())
                .as("AC-1 must include TASK_AC-1_COMPLETED")
                .contains("TASK_AC-1_COMPLETED")
                .as("AC-1 must NOT include TASK_AC-10_COMPLETED via substring matching")
                .doesNotContain("TASK_AC-10_COMPLETED");

        // Verify AC-10 evidence
        CriterionEvidenceItem ac10Item = evidence.criteriaEvidence().stream()
                .filter(c -> "AC-10".equals(c.criterionId()))
                .findFirst()
                .orElseThrow();

        assertThat(ac10Item.plannedTaskIds())
                .as("AC-10 must include TASK-10")
                .contains("TASK-10")
                .as("AC-10 must NOT include TASK-1")
                .doesNotContain("TASK-1");

        assertThat(ac10Item.relevantEventTypes())
                .as("AC-10 must include TASK_AC-10_COMPLETED")
                .contains("TASK_AC-10_COMPLETED")
                .as("AC-10 must NOT include TASK_AC-1_COMPLETED")
                .doesNotContain("TASK_AC-1_COMPLETED");
    }

    @Test
    @DisplayName("Regression: textContainsCriterionToken requires exact normalized token match")
    void textContainsCriterionTokenRequiresExactNormalizedTokenMatch() {
        // AC-10 in text must NOT match AC-1
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("Completed task for AC-10", "AC-1")).isFalse();
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("EVENT_AC-10_VERIFIED", "AC-1")).isFalse();
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("AC-10: Token policy", "AC-1")).isFalse();
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("AC-10", "AC-1")).isFalse();

        // Exact AC-1 matches with surrounding punctuation/casing must match
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("Completed task for AC-1.", "AC-1")).isTrue();
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("Completed task for (ac-1)", "AC-1")).isTrue();
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("EVENT_AC-1_VERIFIED", "AC-1")).isTrue();
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("AC-1: Shorten URL", "ac-1")).isTrue();
        assertThat(WorkflowEvidenceService.textContainsCriterionToken("AC-1", "AC-1")).isTrue();
    }
}
