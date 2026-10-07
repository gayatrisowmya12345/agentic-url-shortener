package com.linkforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.linkforge.domain.workflow.implementation.TestReportItem;
import com.linkforge.domain.workflow.release.ReleaseReadinessOutcome;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.release.ReleaseReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReleaseReadinessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ReleaseReadinessService releaseReadinessService;

    @BeforeEach
    void setUp() {
        workflowRepository.clear();
    }

    private WorkflowRun createSuccessfulWorkflowRun(String req) {
        WorkflowRun run = new WorkflowRun(req);
        run.setAcceptanceCriteria(List.of("AC-1: Custom alias validation bounds", "AC-2: Base62 token generation"));

        FileChangeProposal change = FileChangeProposal.of(
                "src/main/java/com/linkforge/service/link/AliasValidator.java",
                FileChangeOperation.MODIFY,
                "public class AliasValidator {}",
                "hash1",
                "TASK-1",
                "AC-1",
                "SECURITY",
                "Alias bounds"
        );
        ImplementationProposal proposal = ImplementationProposal.supported("ALIAS_VALIDATION", List.of(change));
        run.setImplementationProposal(proposal);
        run.setCurrentPlanHash(proposal.proposalHash());

        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead-reviewer", proposal.proposalHash(), "Approved");
        run.setApproval(approval);

        List<TestReportItem> tests = List.of(
                new TestReportItem("CustomAliasValidationTest", "validatesCustomAliasBounds", "PASSED", 120, null, List.of("AC-1")),
                new TestReportItem("TokenPolicyTest", "generatesBase62Tokens", "PASSED", 95, null, List.of("AC-2"))
        );

        BuildValidationResult buildValidation = new BuildValidationResult(
                "./mvnw clean verify", 0, 1500, "BUILD SUCCESS", "SUCCESS", Instant.now(), true, tests
        );

        GovernedExecutionRecord execRecord = new GovernedExecutionRecord(
                "exec-1", run.getId(), "COMPLETED", WorkflowStage.FINISHED.name(),
                proposal.proposalHash(), proposal, List.of(), buildValidation, null,
                null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(execRecord);
        run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);

        releaseReadinessService.evaluateReleaseReadiness(run);
        return workflowRepository.save(run);
    }

    @Test
    @DisplayName("Release Readiness 1: GET /release endpoint returns computed outcome and deployment is NOT_SUPPORTED")
    void getReleaseReadinessEndpoint() throws Exception {
        WorkflowRun run = createSuccessfulWorkflowRun("Build a URL shortener with alias validation bounds");

        mockMvc.perform(get("/api/v1/workflows/" + run.getId() + "/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomeHash").isNotEmpty())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.readyForRelease").value(true))
                .andExpect(jsonPath("$.deploymentStatus").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.criteriaReadiness", not(empty())))
                .andExpect(jsonPath("$.totalTestsPassed").value(2))
                .andExpect(jsonPath("$.totalTestsFailed").value(0))
                .andExpect(jsonPath("$.missingCriteriaCoverage", empty()));
    }

    @Test
    @DisplayName("Release Readiness 2: Successful approval via authorized POST /release/approve endpoint")
    void successfulReleaseApproval() throws Exception {
        WorkflowRun run = createSuccessfulWorkflowRun("Build a URL shortener with alias validation bounds");
        String outcomeHash = run.getReleaseReadiness().outcomeHash();

        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "outcomeHash": "%s",
                  "approver": "release-officer@linkforge.io",
                  "comments": "Audited build and test evidence; approved for release readiness"
                }
                """, outcomeHash);

        mockMvc.perform(post("/api/v1/workflows/" + run.getId() + "/release/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(run.getId()))
                .andExpect(jsonPath("$.releaseReadiness.status").value("APPROVED"))
                .andExpect(jsonPath("$.releaseReadiness.approval.decision").value("APPROVED"))
                .andExpect(jsonPath("$.releaseReadiness.approval.approver").value("release-officer@linkforge.io"))
                .andExpect(jsonPath("$.releaseReadiness.approval.outcomeHash").value(outcomeHash))
                .andExpect(jsonPath("$.releaseReadiness.deploymentStatus").value("NOT_SUPPORTED"));

        // Verify audit event persisted
        WorkflowRun updated = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getEvents()).anyMatch(e -> "RELEASE_APPROVED".equals(e.eventType()));
        assertThat(updated.getReleaseReadiness().deploymentStatus()).isEqualTo("NOT_SUPPORTED");
    }

    @Test
    @DisplayName("Release Readiness 3: Explicit release rejection persists REJECTED record and event")
    void releaseRejection() throws Exception {
        WorkflowRun run = createSuccessfulWorkflowRun("Build a URL shortener with alias validation bounds");
        String outcomeHash = run.getReleaseReadiness().outcomeHash();

        String rejectPayload = String.format("""
                {
                  "decision": "REJECTED",
                  "outcomeHash": "%s",
                  "approver": "compliance-officer@linkforge.io",
                  "comments": "Rejected: missing operational runbook signoff"
                }
                """, outcomeHash);

        mockMvc.perform(post("/api/v1/workflows/" + run.getId() + "/release/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseReadiness.status").value("REJECTED"))
                .andExpect(jsonPath("$.releaseReadiness.approval.decision").value("REJECTED"))
                .andExpect(jsonPath("$.releaseReadiness.approval.approver").value("compliance-officer@linkforge.io"));

        WorkflowRun updated = workflowRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getEvents()).anyMatch(e -> "RELEASE_REJECTED".equals(e.eventType()));
    }

    @Test
    @DisplayName("Release Readiness 4: Tampered outcome hash is rejected with HTTP 400 INVALID_PLAN_HASH")
    void tamperedOutcomeHashRejected() throws Exception {
        WorkflowRun run = createSuccessfulWorkflowRun("Build a URL shortener with alias validation bounds");

        String tamperedPayload = """
                {
                  "decision": "APPROVED",
                  "outcomeHash": "tampered-sha256-hash-value-0000000000",
                  "approver": "attacker"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + run.getId() + "/release/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PLAN_HASH"));
    }

    @Test
    @DisplayName("Release Readiness 5: Incomplete build/test evidence blocks approval")
    void incompleteEvidenceBlocksApproval() throws Exception {
        WorkflowRun run = new WorkflowRun("Greenfield requirement");
        run.setAcceptanceCriteria(List.of("AC-1: Token generation"));
        workflowRepository.save(run);
        ReleaseReadinessOutcome outcome = releaseReadinessService.evaluateReleaseReadiness(run);

        String payload = String.format("""
                {
                  "decision": "APPROVED",
                  "outcomeHash": "%s"
                }
                """, outcome.outcomeHash());

        mockMvc.perform(post("/api/v1/workflows/" + run.getId() + "/release/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));
    }

    @Test
    @DisplayName("Release Readiness 6: Failed test suite blocks release readiness approval")
    void failedTestsBlockApproval() throws Exception {
        WorkflowRun run = new WorkflowRun("Requirement with test failures");
        run.setAcceptanceCriteria(List.of("AC-1: Feature"));
        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead", "plan-1", "Approve");
        run.setApproval(approval);

        List<TestReportItem> failedTests = List.of(
                new TestReportItem("FeatureTest", "testOne", "FAILED", 80, "Assertion failed", List.of("AC-1"))
        );
        BuildValidationResult failedBuild = new BuildValidationResult(
                "./mvnw clean verify", 1, 1000, "BUILD FAILURE", "BUILD_FAILED", Instant.now(), true, failedTests
        );
        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-2", run.getId(), "FAILED", WorkflowStage.FINISHED.name(),
                "plan-1", null, List.of(), failedBuild, null, "Failed", Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);
        run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
        workflowRepository.save(run);

        ReleaseReadinessOutcome outcome = releaseReadinessService.evaluateReleaseReadiness(run);
        assertThat(outcome.readyForRelease()).isFalse();

        String payload = String.format("""
                {
                  "decision": "APPROVED",
                  "outcomeHash": "%s"
                }
                """, outcome.outcomeHash());

        mockMvc.perform(post("/api/v1/workflows/" + run.getId() + "/release/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Release Readiness 7: Incomplete criterion coverage blocks release readiness approval")
    void incompleteCriterionCoverageBlocksApproval() throws Exception {
        WorkflowRun run = new WorkflowRun("Requirement with partial coverage");
        run.setAcceptanceCriteria(List.of("AC-1: Covered criterion", "AC-2: Uncovered criterion"));
        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead", "plan-1", "Approve");
        run.setApproval(approval);

        List<TestReportItem> partialTests = List.of(
                new TestReportItem("CoveredTest", "testOne", "PASSED", 50, null, List.of("AC-1"))
        );
        BuildValidationResult build = new BuildValidationResult(
                "./mvnw clean verify", 0, 1000, "BUILD SUCCESS", "SUCCESS", Instant.now(), true, partialTests
        );
        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-3", run.getId(), "COMPLETED", WorkflowStage.FINISHED.name(),
                "plan-1", null, List.of(), build, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);
        run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
        workflowRepository.save(run);

        ReleaseReadinessOutcome outcome = releaseReadinessService.evaluateReleaseReadiness(run);
        assertThat(outcome.missingCriteriaCoverage()).contains("AC-2");
        assertThat(outcome.readyForRelease()).isFalse();

        String payload = String.format("""
                {
                  "decision": "APPROVED",
                  "outcomeHash": "%s"
                }
                """, outcome.outcomeHash());

        mockMvc.perform(post("/api/v1/workflows/" + run.getId() + "/release/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Release Readiness 8: Blocked execution and rollback states strictly block approval")
    void blockedAndRolledBackStatesBlockApproval() {
        WorkflowRun blockedRun = new WorkflowRun("Blocked run");
        blockedRun.setAcceptanceCriteria(List.of("AC-1: Token"));
        blockedRun.transitionTo(WorkflowStatus.BLOCKED, WorkflowStage.BLOCKED);
        workflowRepository.save(blockedRun);
        ReleaseReadinessOutcome blockedOutcome = releaseReadinessService.evaluateReleaseReadiness(blockedRun);

        assertThatThrownBy(() -> releaseReadinessService.approveReleaseReadiness(
                blockedRun.getId(), blockedOutcome.outcomeHash(), "APPROVED", "approver", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blocked");

        WorkflowRun rolledBackRun = new WorkflowRun("Rolled back run");
        rolledBackRun.setAcceptanceCriteria(List.of("AC-1: Token"));
        rolledBackRun.transitionTo(WorkflowStatus.ROLLED_BACK, WorkflowStage.ROLLED_BACK);
        workflowRepository.save(rolledBackRun);
        ReleaseReadinessOutcome rbOutcome = releaseReadinessService.evaluateReleaseReadiness(rolledBackRun);

        assertThatThrownBy(() -> releaseReadinessService.approveReleaseReadiness(
                rolledBackRun.getId(), rbOutcome.outcomeHash(), "APPROVED", "approver", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rolled back");
    }

    @Test
    @DisplayName("Release Readiness 9: Passing tests with wrong criterion lineage strictly blocks release readiness")
    void wrongCriterionLineageBlocksReleaseReadiness() {
        WorkflowRun run = new WorkflowRun("Requirement with mismatched criterion lineage");
        run.setAcceptanceCriteria(List.of("AC-1: Custom alias validation"));
        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead", "plan-1", "Approve");
        run.setApproval(approval);

        // Discovered passing test has lineage for AC-2, NOT AC-1
        List<TestReportItem> tests = List.of(
                new TestReportItem("OtherFeatureTest", "testSomethingElse", "PASSED", 60, null, List.of("AC-2"))
        );
        BuildValidationResult build = new BuildValidationResult(
                "./mvnw clean verify", 0, 1000, "BUILD SUCCESS", "SUCCESS", Instant.now(), true, tests
        );
        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-mismatch", run.getId(), "COMPLETED", WorkflowStage.FINISHED.name(),
                "plan-1", null, List.of(), build, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);
        run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
        workflowRepository.save(run);

        ReleaseReadinessOutcome outcome = releaseReadinessService.evaluateReleaseReadiness(run);
        assertThat(outcome.readyForRelease()).isFalse();
        assertThat(outcome.missingCriteriaCoverage()).contains("AC-1");
        assertThat(outcome.criteriaReadiness().get(0).status()).isEqualTo("UNVERIFIED");
        assertThat(outcome.criteriaReadiness().get(0).coveredByPassingTest()).isFalse();
    }

    @Test
    @DisplayName("Release Readiness 10: Multiple acceptance criteria require distinct matching passing tests for readiness")
    void multipleCriteriaRequireMatchingPassingTests() {
        WorkflowRun run = new WorkflowRun("Requirement with multiple criteria");
        run.setAcceptanceCriteria(List.of(
                "AC-1: Alias bounds validation",
                "AC-2: Destination domain restriction",
                "AC-3: Base62 token generation"
        ));
        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead", "plan-1", "Approve");
        run.setApproval(approval);

        // Only AC-1 and AC-2 have passing tests; AC-3 is missing
        List<TestReportItem> partialTests = List.of(
                new TestReportItem("AliasTest", "validatesBounds", "PASSED", 70, null, List.of("AC-1")),
                new TestReportItem("DomainTest", "restrictsDomain", "PASSED", 80, null, List.of("AC-2"))
        );
        BuildValidationResult partialBuild = new BuildValidationResult(
                "./mvnw clean verify", 0, 1200, "BUILD SUCCESS", "SUCCESS", Instant.now(), true, partialTests
        );
        GovernedExecutionRecord partialRecord = new GovernedExecutionRecord(
                "exec-multi-partial", run.getId(), "COMPLETED", WorkflowStage.FINISHED.name(),
                "plan-1", null, List.of(), partialBuild, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(partialRecord);
        run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
        workflowRepository.save(run);

        ReleaseReadinessOutcome partialOutcome = releaseReadinessService.evaluateReleaseReadiness(run);
        assertThat(partialOutcome.readyForRelease()).isFalse();
        assertThat(partialOutcome.missingCriteriaCoverage()).contains("AC-3");

        // Now provide all three matching tests
        List<TestReportItem> completeTests = List.of(
                new TestReportItem("AliasTest", "validatesBounds", "PASSED", 70, null, List.of("AC-1")),
                new TestReportItem("DomainTest", "restrictsDomain", "PASSED", 80, null, List.of("AC-2")),
                new TestReportItem("TokenTest", "generatesBase62", "PASSED", 90, null, List.of("AC-3"))
        );
        BuildValidationResult completeBuild = new BuildValidationResult(
                "./mvnw clean verify", 0, 1500, "BUILD SUCCESS", "SUCCESS", Instant.now(), true, completeTests
        );
        GovernedExecutionRecord completeRecord = new GovernedExecutionRecord(
                "exec-multi-complete", run.getId(), "COMPLETED", WorkflowStage.FINISHED.name(),
                "plan-1", null, List.of(), completeBuild, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(completeRecord);
        workflowRepository.save(run);

        ReleaseReadinessOutcome completeOutcome = releaseReadinessService.evaluateReleaseReadiness(run);
        assertThat(completeOutcome.readyForRelease()).isTrue();
        assertThat(completeOutcome.missingCriteriaCoverage()).isEmpty();
        assertThat(completeOutcome.status()).isEqualTo("READY");
        assertThat(completeOutcome.criteriaReadiness()).allMatch(c -> c.coveredByPassingTest() && "VERIFIED".equals(c.status()));
    }

    @Test
    @DisplayName("Release Readiness 11: Successful build without actual discovered test reports blocks release readiness")
    void successfulBuildWithoutDiscoveredTestsBlocksReleaseReadiness() {
        WorkflowRun run = new WorkflowRun("Requirement with zero test reports");
        run.setAcceptanceCriteria(List.of("AC-1: Custom alias validation"));
        WorkflowApproval approval = WorkflowApproval.of(run.getId(), "APPROVED", "lead", "plan-1", "Approve");
        run.setApproval(approval);

        // Build succeeded, but zero test reports were discovered from Surefire
        BuildValidationResult buildWithoutTests = new BuildValidationResult(
                "./mvnw clean verify", 0, 1000, "BUILD SUCCESS", "SUCCESS", Instant.now(), true, List.of()
        );
        GovernedExecutionRecord record = new GovernedExecutionRecord(
                "exec-no-tests", run.getId(), "COMPLETED", WorkflowStage.FINISHED.name(),
                "plan-1", null, List.of(), buildWithoutTests, null, null, Instant.now(), Instant.now()
        );
        run.setExecutionRecord(record);
        run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
        workflowRepository.save(run);

        ReleaseReadinessOutcome outcome = releaseReadinessService.evaluateReleaseReadiness(run);
        // Release readiness CANNOT infer test passing from build success alone!
        assertThat(outcome.readyForRelease()).isFalse();
        assertThat(outcome.missingCriteriaCoverage()).contains("AC-1");
        assertThat(outcome.totalTestsPassed()).isEqualTo(0);
    }
}
