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
}
