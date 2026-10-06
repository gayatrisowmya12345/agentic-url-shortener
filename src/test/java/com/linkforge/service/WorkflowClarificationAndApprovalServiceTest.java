package com.linkforge.service;

import com.linkforge.agent.DependencyAwarePlannerAgent;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.agent.ScenarioClassifierAgent;
import com.linkforge.domain.workflow.PlanHasher;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowClarification;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.specialist.SpecialistRole;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.inspection.CodebaseInspector;
import com.linkforge.service.security.InvalidPlanHashException;
import com.linkforge.domain.workflow.AgentDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowClarificationAndApprovalServiceTest {

    private WorkflowOrchestrator orchestrator;
    private WorkflowRepository repository;

    @BeforeEach
    void setUp() {
        RequirementInterpreterAgent interpreter = new RequirementInterpreterAgent();
        DependencyAwarePlannerAgent planner = new DependencyAwarePlannerAgent();
        repository = new WorkflowRepository();
        orchestrator = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                interpreter,
                planner,
                repository
        );
    }

    @Test
    @DisplayName("Ambiguous requirement pauses in WAITING_FOR_CLARIFICATION with no tasks or coordination")
    void ambiguousRequirementPausesWithoutPlanningOrCoordination() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");

        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.SCENARIO_CLASSIFICATION);
        assertThat(run.getUnansweredQuestions()).isNotEmpty();
        assertThat(run.getTasks()).isEmpty();
        assertThat(run.getSpecialistInvocations()).isEmpty();
        assertThat(run.getCurrentPlanHash()).isNull();
    }

    @Test
    @DisplayName("Blank or whitespace clarification submission is rejected")
    void blankClarificationSubmissionIsRejected() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        assertThatThrownBy(() -> orchestrator.submitClarification(run.getId(), "   ", null, "operator-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank or incomplete");

        assertThatThrownBy(() -> orchestrator.submitClarification(run.getId(), null, null, "operator-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank or incomplete");
    }

    @Test
    @DisplayName("Clarification preserves original requirement and records submitter history")
    void clarificationPreservesOriginalRequirementAndRecordsHistory() {
        String originalReq = "make links faster and safer";
        WorkflowRun run = orchestrator.startWorkflow(originalReq);
        assertThat(run.getOriginalRequirement()).isEqualTo(originalReq);

        Optional<WorkflowRun> resumed = orchestrator.submitClarification(
                run.getId(),
                "Build a greenfield REST URL shortener with token generation and redirection",
                null,
                "alice-operator"
        );

        assertThat(resumed).isPresent();
        WorkflowRun updated = resumed.get();
        assertThat(updated.getOriginalRequirement()).isEqualTo(originalReq);
        assertThat(updated.getClarificationHistory()).hasSize(1);

        WorkflowClarification entry = updated.getClarificationHistory().get(0);
        assertThat(entry.submittedBy()).isEqualTo("alice-operator");
        assertThat(entry.clarificationText()).contains("Build a greenfield REST URL shortener");
        assertThat(entry.submittedAt()).isNotNull();
    }

    @Test
    @DisplayName("Unresolved clarification leaves workflow paused in WAITING_FOR_CLARIFICATION")
    void unresolvedClarificationRemainsPaused() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        // Submit clarification that is still vague and lacks concrete actions
        Optional<WorkflowRun> resumed = orchestrator.submitClarification(
                run.getId(),
                "please optimize links and make it fast",
                null,
                "operator-1"
        );

        assertThat(resumed).isPresent();
        WorkflowRun stillPaused = resumed.get();
        assertThat(stillPaused.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);
        assertThat(stillPaused.getTasks()).isEmpty();
        assertThat(stillPaused.getCurrentPlanHash()).isNull();
        assertThat(stillPaused.getClarificationHistory()).hasSize(1);
    }

    @Test
    @DisplayName("Valid clarification re-analyzes requirement and pauses at WAITING_FOR_APPROVAL")
    void validClarificationReanalyzesAndPausesForApproval() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        Optional<WorkflowRun> resumed = orchestrator.submitClarification(
                run.getId(),
                "Build a greenfield URL shortener REST service with Base62 token generation and redirection",
                null,
                "bob-operator"
        );

        assertThat(resumed).isPresent();
        WorkflowRun paused = resumed.get();
        assertThat(paused.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);
        assertThat(paused.getCurrentStage()).isEqualTo(WorkflowStage.PLAN_APPROVAL);
        assertThat(paused.getTasks()).hasSize(5);
        assertThat(paused.getCurrentPlanHash()).isNotBlank();
        // Coordination must NOT have run yet
        assertThat(paused.getSpecialistInvocations()).isEmpty();
    }

    @Test
    @DisplayName("Coordination cannot continue before human plan approval")
    void coordinationCannotContinueBeforeApproval() {
        WorkflowRun run = orchestrator.startWorkflow("Build a URL shortener service with redirects");

        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.PLAN_APPROVAL);
        assertThat(run.getCurrentPlanHash()).isNotBlank();
        assertThat(run.getTasks()).isNotEmpty();
        assertThat(run.getSpecialistInvocations()).isEmpty();
        assertThat(run.getApproval()).isNull();
    }

    @Test
    @DisplayName("Valid plan approval unblocks specialist coordination to completion")
    void validApprovalExecutesCoordinationToCompletion() {
        WorkflowRun run = orchestrator.startWorkflow("Build a URL shortener service with redirects");
        String planHash = run.getCurrentPlanHash();

        Optional<WorkflowRun> approvedOpt = orchestrator.approvePlan(
                run.getId(),
                "APPROVED",
                planHash,
                "security-reviewer",
                "Plan reviewed and verified compliant"
        );

        assertThat(approvedOpt).isPresent();
        WorkflowRun approvedRun = approvedOpt.get();
        assertThat(approvedRun.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(approvedRun.getCurrentStage()).isEqualTo(WorkflowStage.FINISHED);
        assertThat(approvedRun.getSpecialistInvocations()).hasSize(5);

        WorkflowApproval approval = approvedRun.getApproval();
        assertThat(approval).isNotNull();
        assertThat(approval.isApproved()).isTrue();
        assertThat(approval.approver()).isEqualTo("security-reviewer");
        assertThat(approval.planHash()).isEqualTo(planHash);
        assertThat(approval.comments()).contains("compliant");
        assertThat(approval.decidedAt()).isNotNull();
    }

    @Test
    @DisplayName("Plan rejection marks workflow REJECTED and halts execution before coordination")
    void planRejectionMarksWorkflowRejectedWithoutCoordination() {
        WorkflowRun run = orchestrator.startWorkflow("Build a URL shortener service with redirects");
        String planHash = run.getCurrentPlanHash();

        Optional<WorkflowRun> rejectedOpt = orchestrator.approvePlan(
                run.getId(),
                "REJECTED",
                planHash,
                "qa-lead",
                "Rejected due to missing non-functional test criteria"
        );

        assertThat(rejectedOpt).isPresent();
        WorkflowRun rejectedRun = rejectedOpt.get();
        assertThat(rejectedRun.getStatus()).isEqualTo(WorkflowStatus.REJECTED);
        assertThat(rejectedRun.getCurrentStage()).isEqualTo(WorkflowStage.PLAN_APPROVAL);
        assertThat(rejectedRun.getSpecialistInvocations()).isEmpty();

        WorkflowApproval approval = rejectedRun.getApproval();
        assertThat(approval).isNotNull();
        assertThat(approval.isRejected()).isTrue();
        assertThat(approval.approver()).isEqualTo("qa-lead");
        assertThat(approval.comments()).contains("missing non-functional test criteria");
    }

    @Test
    @DisplayName("Wrong or mismatched plan hash is rejected with InvalidPlanHashException")
    void wrongPlanHashIsRejected() {
        WorkflowRun run = orchestrator.startWorkflow("Build a URL shortener service with redirects");

        assertThatThrownBy(() -> orchestrator.approvePlan(
                run.getId(),
                "APPROVED",
                "incorrect-or-altered-hash-12345",
                "approver-1",
                "Approval attempt"
        )).isInstanceOf(InvalidPlanHashException.class)
                .hasMessageContaining("does not match current plan hash");
    }

    @Test
    @DisplayName("Blank or missing plan hash is rejected with IllegalArgumentException")
    void blankPlanHashIsRejected() {
        WorkflowRun run = orchestrator.startWorkflow("Build a URL shortener service with redirects");

        assertThatThrownBy(() -> orchestrator.approvePlan(
                run.getId(),
                "APPROVED",
                "   ",
                "approver-1",
                "Approval attempt"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Plan hash cannot be blank");
    }

    @Test
    @DisplayName("Modifying plan invalidates prior approval")
    void planModificationInvalidatesApproval() {
        WorkflowRun run = new WorkflowRun("Build URL shortener");
        List<PlannedTask> tasksV1 = List.of(
                new PlannedTask("TASK-1", "Task 1", "Desc 1", List.of(), "PENDING", SpecialistRole.API_BEHAVIOR.name())
        );
        run.setTasks(tasksV1);
        String hashV1 = run.getCurrentPlanHash();

        WorkflowApproval approvalV1 = WorkflowApproval.of(run.getId(), "APPROVED", "approver", hashV1, "Approved");
        run.setApproval(approvalV1);
        assertThat(run.getApproval()).isNotNull();

        // Mutate tasks to V2
        List<PlannedTask> tasksV2 = List.of(
                new PlannedTask("TASK-1", "Task 1", "Desc 1", List.of(), "PENDING", SpecialistRole.API_BEHAVIOR.name()),
                new PlannedTask("TASK-2", "Task 2 - Extra", "Desc 2", List.of("TASK-1"), "PENDING", SpecialistRole.DATA_PERSISTENCE.name())
        );
        run.setTasks(tasksV2);

        // Approval must be automatically invalidated
        assertThat(run.getCurrentPlanHash()).isNotEqualTo(hashV1);
        assertThat(run.getApproval()).isNull();
    }

    @Test
    @DisplayName("Idempotency: duplicate approval submission returns current run without re-executing coordination")
    void duplicateApprovalIsIdempotent() {
        WorkflowRun run = orchestrator.startWorkflow("Build a URL shortener service with redirects");
        String planHash = run.getCurrentPlanHash();

        WorkflowRun firstApproval = orchestrator.approvePlan(
                run.getId(),
                "APPROVED",
                planHash,
                "approver-1",
                "First approval"
        ).orElseThrow();

        assertThat(firstApproval.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        int invocationCount = firstApproval.getSpecialistInvocations().size();

        // Resubmit identical approval
        WorkflowRun duplicateApproval = orchestrator.approvePlan(
                run.getId(),
                "APPROVED",
                planHash,
                "approver-1",
                "First approval"
        ).orElseThrow();

        assertThat(duplicateApproval.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(duplicateApproval.getSpecialistInvocations()).hasSize(invocationCount);
    }

    @Test
    @DisplayName("Repeating the same clarification while still WAITING_FOR_CLARIFICATION keeps one history entry and does not invoke analysis again")
    void repeatingSameClarificationWhileWaitingKeepsOneHistoryEntryAndDoesNotReanalyze() {
        RequirementInterpreterAgent interpreter = Mockito.spy(new RequirementInterpreterAgent());
        DependencyAwarePlannerAgent planner = new DependencyAwarePlannerAgent();
        WorkflowRepository repo = new WorkflowRepository();
        WorkflowOrchestrator customOrchestrator = new WorkflowOrchestrator(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                interpreter,
                planner,
                repo
        );

        WorkflowRun run = customOrchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        // Submit first clarification that leaves it unresolved
        WorkflowRun first = customOrchestrator.submitClarification(
                run.getId(),
                "please optimize links and make it fast",
                null,
                "operator-1"
        ).orElseThrow();

        assertThat(first.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);
        assertThat(first.getClarificationHistory()).hasSize(1);
        int interpreterInvocations = Mockito.mockingDetails(interpreter).getInvocations().size();

        // Repeat the exact same clarification
        WorkflowRun retry = customOrchestrator.submitClarification(
                run.getId(),
                "please optimize links and make it fast",
                null,
                "operator-1"
        ).orElseThrow();

        // Must remain WAITING_FOR_CLARIFICATION with exactly 1 history entry
        assertThat(retry.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);
        assertThat(retry.getClarificationHistory()).hasSize(1);

        // Must not invoke interpreter analysis again
        int afterInvocations = Mockito.mockingDetails(interpreter).getInvocations().size();
        assertThat(afterInvocations).isEqualTo(interpreterInvocations);
    }

    @Test
    @DisplayName("A different clarification can still be submitted while clarification is pending")
    void differentClarificationCanBeSubmittedWhilePending() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        // Submit first clarification that leaves it unresolved
        WorkflowRun first = orchestrator.submitClarification(
                run.getId(),
                "please optimize links and make it fast",
                null,
                "operator-1"
        ).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);
        assertThat(first.getClarificationHistory()).hasSize(1);

        // Submit different clarification with concrete greenfield requirement
        WorkflowRun second = orchestrator.submitClarification(
                run.getId(),
                "Build a greenfield URL shortener REST service with Base62 token generation and redirection",
                null,
                "operator-2"
        ).orElseThrow();

        // Both clarifications are preserved in history
        assertThat(second.getClarificationHistory()).hasSize(2);
        assertThat(second.getClarificationHistory().get(0).clarificationText()).contains("please optimize links");
        assertThat(second.getClarificationHistory().get(1).clarificationText()).contains("Build a greenfield URL shortener");
        assertThat(second.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);
        assertThat(second.getCurrentStage()).isEqualTo(WorkflowStage.PLAN_APPROVAL);
        assertThat(second.getTasks()).isNotEmpty();
    }

    @Test
    @DisplayName("One scenario-classification run records exactly one classification decision")
    void scenarioClassificationRunRecordsExactlyOneClassificationDecision() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");

        List<AgentDecision> classificationDecisions = run.getAgentDecisions().stream()
                .filter(d -> ScenarioClassifierAgent.AGENT_NAME.equals(d.agentName()))
                .toList();

        assertThat(classificationDecisions)
                .as("One scenario-classification run must produce exactly one decision entry")
                .hasSize(1);
        assertThat(run.getClassificationDecision()).isNotNull();
        assertThat(run.getClassificationDecision().agentName()).isEqualTo(ScenarioClassifierAgent.AGENT_NAME);
    }
}
