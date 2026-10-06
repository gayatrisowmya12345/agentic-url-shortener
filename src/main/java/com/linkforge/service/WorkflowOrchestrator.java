package com.linkforge.service;

import com.linkforge.agent.DependencyAwarePlannerAgent;
import com.linkforge.agent.RequirementInterpretationResult;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.agent.TaskPlanningResult;
import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class WorkflowOrchestrator {

    private final RequirementInterpreterAgent requirementInterpreterAgent;
    private final DependencyAwarePlannerAgent plannerAgent;
    private final WorkflowRepository workflowRepository;

    public WorkflowOrchestrator(
            RequirementInterpreterAgent requirementInterpreterAgent,
            DependencyAwarePlannerAgent plannerAgent,
            WorkflowRepository workflowRepository
    ) {
        this.requirementInterpreterAgent = requirementInterpreterAgent;
        this.plannerAgent = plannerAgent;
        this.workflowRepository = workflowRepository;
    }

    public WorkflowRun startWorkflow(String rawRequirement) {
        WorkflowRun run = new WorkflowRun(rawRequirement);
        run.addEvent(WorkflowEvent.of(
                "WORKFLOW_INITIALIZED",
                WorkflowStage.INTAKE.name(),
                "Workflow instance initialized for requirement intake."
        ));

        // Advance to Requirement Interpretation
        run.transitionTo(WorkflowStatus.IN_PROGRESS, WorkflowStage.REQUIREMENT_INTERPRETATION);
        run.addEvent(WorkflowEvent.of(
                "INTERPRETATION_STARTED",
                WorkflowStage.REQUIREMENT_INTERPRETATION.name(),
                "Delegating requirement analysis to deterministic interpreter agent."
        ));

        RequirementInterpretationResult interpretation = requirementInterpreterAgent.interpret(rawRequirement);

        run.addAgentDecision(AgentDecision.of(
                RequirementInterpreterAgent.AGENT_NAME,
                interpretation.decisionSummary(),
                interpretation.rationale(),
                interpretation.metadata()
        ));

        if (!interpretation.clear()) {
            run.setUnansweredQuestions(interpretation.unansweredQuestions());
            run.addEvent(WorkflowEvent.of(
                    "AMBIGUITY_DETECTED",
                    WorkflowStage.REQUIREMENT_INTERPRETATION.name(),
                    "Requirement lacks verifiable bounds; " + interpretation.unansweredQuestions().size() + " questions pending clarification."
            ));
            run.transitionTo(WorkflowStatus.WAITING_FOR_CLARIFICATION, WorkflowStage.REQUIREMENT_INTERPRETATION);
            run.addEvent(WorkflowEvent.of(
                    "WORKFLOW_PAUSED",
                    WorkflowStage.REQUIREMENT_INTERPRETATION.name(),
                    "Workflow paused in WAITING_FOR_CLARIFICATION stage. Awaiting clarification from user."
            ));
            return workflowRepository.save(run);
        }

        // Clear requirement branch
        run.setAcceptanceCriteria(interpretation.acceptanceCriteria());
        run.addEvent(WorkflowEvent.of(
                "REQUIREMENT_ACCEPTED",
                WorkflowStage.REQUIREMENT_INTERPRETATION.name(),
                "Requirement validated; synthesized " + interpretation.acceptanceCriteria().size() + " acceptance criteria."
        ));

        // Advance to Task Planning
        run.transitionTo(WorkflowStatus.IN_PROGRESS, WorkflowStage.TASK_PLANNING);
        run.addEvent(WorkflowEvent.of(
                "PLANNING_STARTED",
                WorkflowStage.TASK_PLANNING.name(),
                "Delegating task decomposition to dependency-aware planner agent."
        ));

        TaskPlanningResult planningResult = plannerAgent.plan(interpretation.acceptanceCriteria(), rawRequirement);

        run.addAgentDecision(AgentDecision.of(
                DependencyAwarePlannerAgent.AGENT_NAME,
                planningResult.planSummary(),
                planningResult.rationale(),
                planningResult.metadata()
        ));

        run.setTasks(planningResult.tasks());
        run.addEvent(WorkflowEvent.of(
                "PLAN_GENERATED",
                WorkflowStage.TASK_PLANNING.name(),
                "Generated dependency graph with " + planningResult.tasks().size() + " tasks."
        ));

        // Transition to Finished & Completed
        run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
        run.addEvent(WorkflowEvent.of(
                "WORKFLOW_COMPLETED",
                WorkflowStage.FINISHED.name(),
                "Agentic workflow vertical slice completed successfully."
        ));

        return workflowRepository.save(run);
    }

    public Optional<WorkflowRun> getWorkflowRun(String id) {
        return workflowRepository.findById(id);
    }
}
