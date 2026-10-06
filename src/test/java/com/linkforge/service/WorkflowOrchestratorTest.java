package com.linkforge.service;

import com.linkforge.agent.DependencyAwarePlannerAgent;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowOrchestratorTest {

    private WorkflowOrchestrator orchestrator;
    private WorkflowRepository repository;

    @BeforeEach
    void setUp() {
        RequirementInterpreterAgent interpreter = new RequirementInterpreterAgent();
        DependencyAwarePlannerAgent planner = new DependencyAwarePlannerAgent();
        repository = new WorkflowRepository();
        orchestrator = new WorkflowOrchestrator(interpreter, planner, repository);
    }

    @Test
    @DisplayName("Clear requirement completes workflow with acceptance criteria, task plan, and full event history")
    void executeClearWorkflow() {
        String requirement = "Build a REST API to shorten URLs and redirect requests to destination targets";

        WorkflowRun run = orchestrator.startWorkflow(requirement);

        assertThat(run).isNotNull();
        assertThat(run.getId()).isNotBlank();
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_APPROVAL);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.PLAN_APPROVAL);
        assertThat(run.getCurrentPlanHash()).isNotBlank();

        // Verify Acceptance Criteria
        assertThat(run.getAcceptanceCriteria()).isNotEmpty();
        assertThat(run.getUnansweredQuestions()).isEmpty();

        // Verify Tasks
        assertThat(run.getTasks()).hasSize(5);

        // Approve the plan to run specialist coordination to completion
        WorkflowRun completed = orchestrator.approvePlan(
                run.getId(),
                "APPROVED",
                run.getCurrentPlanHash(),
                "test-approver",
                "Approved for testing"
        ).orElseThrow();

        assertThat(completed.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.getCurrentStage()).isEqualTo(WorkflowStage.FINISHED);

        // Verify Event History
        List<WorkflowEvent> events = completed.getEvents();
        assertThat(events).isNotEmpty();
        List<String> eventTypes = events.stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).containsSubsequence(
                "WORKFLOW_INITIALIZED",
                "CLASSIFICATION_STARTED",
                "SCENARIO_CLASSIFIED",
                "INTERPRETATION_STARTED",
                "REQUIREMENT_ACCEPTED",
                "PLANNING_STARTED",
                "PLAN_GENERATED",
                "AWAITING_PLAN_APPROVAL",
                "PLAN_APPROVED",
                "COORDINATION_STARTED",
                "COORDINATION_COMPLETED",
                "WORKFLOW_COMPLETED"
        );
        assertThat(events).allMatch(e -> e.timestamp() != null);

        // Verify Agent Decisions
        List<AgentDecision> decisions = run.getAgentDecisions();
        assertThat(decisions).hasSize(3);
        assertThat(decisions.get(0).agentName()).isEqualTo(com.linkforge.agent.ScenarioClassifierAgent.AGENT_NAME);
        assertThat(decisions.get(0).agentType()).isEqualTo(AgentDecision.DETERMINISTIC_SPECIALIST);
        assertThat(decisions.get(1).agentName()).isEqualTo(RequirementInterpreterAgent.AGENT_NAME);
        assertThat(decisions.get(1).agentType()).isEqualTo(AgentDecision.DETERMINISTIC_SPECIALIST);
        assertThat(decisions.get(2).agentName()).isEqualTo(DependencyAwarePlannerAgent.AGENT_NAME);
        assertThat(decisions.get(2).agentType()).isEqualTo(AgentDecision.DETERMINISTIC_SPECIALIST);

        // Verify Repository Persistence
        Optional<WorkflowRun> retrieved = orchestrator.getWorkflowRun(run.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getId()).isEqualTo(run.getId());
    }

    @Test
    @DisplayName("Ambiguous requirement pauses in WAITING_FOR_CLARIFICATION with unanswered questions")
    void executeAmbiguousWorkflow() {
        String requirement = "make links faster and safer";

        WorkflowRun run = orchestrator.startWorkflow(requirement);

        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.SCENARIO_CLASSIFICATION);

        // Clarification questions recorded
        assertThat(run.getUnansweredQuestions()).isNotEmpty();
        assertThat(run.getAcceptanceCriteria()).isEmpty();

        // Planner should not have run
        assertThat(run.getTasks()).isEmpty();

        // Event history verification
        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains(
                "WORKFLOW_INITIALIZED",
                "CLASSIFICATION_STARTED",
                "SCENARIO_CLASSIFIED",
                "AMBIGUITY_DETECTED",
                "WORKFLOW_PAUSED"
        );
        assertThat(eventTypes).doesNotContain("PLANNING_STARTED", "PLAN_GENERATED", "WORKFLOW_COMPLETED");

        // Classifier decision recorded
        assertThat(run.getAgentDecisions()).hasSize(1);
        assertThat(run.getAgentDecisions().get(0).agentName()).isEqualTo(com.linkforge.agent.ScenarioClassifierAgent.AGENT_NAME);
    }
}
