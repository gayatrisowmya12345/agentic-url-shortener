package com.linkforge.service;

import com.linkforge.agent.DependencyAwarePlannerAgent;
import com.linkforge.agent.RequirementInterpretationResult;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.agent.ScenarioClassifierAgent;
import com.linkforge.agent.TaskPlanningResult;
import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.scenario.ScenarioClassificationResult;
import com.linkforge.domain.workflow.scenario.exception.InspectionException;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.inspection.CodebaseInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private final ScenarioClassifierAgent scenarioClassifierAgent;
    private final CodebaseInspector codebaseInspector;
    private final RequirementInterpreterAgent requirementInterpreterAgent;
    private final DependencyAwarePlannerAgent plannerAgent;
    private final WorkflowRepository workflowRepository;

    public WorkflowOrchestrator(
            RequirementInterpreterAgent requirementInterpreterAgent,
            DependencyAwarePlannerAgent plannerAgent,
            WorkflowRepository workflowRepository
    ) {
        this(
                new ScenarioClassifierAgent(),
                new CodebaseInspector(new CodebaseInspectionProperties()),
                requirementInterpreterAgent,
                plannerAgent,
                workflowRepository
        );
    }

    @Autowired
    public WorkflowOrchestrator(
            ScenarioClassifierAgent scenarioClassifierAgent,
            CodebaseInspector codebaseInspector,
            RequirementInterpreterAgent requirementInterpreterAgent,
            DependencyAwarePlannerAgent plannerAgent,
            WorkflowRepository workflowRepository
    ) {
        this.scenarioClassifierAgent = scenarioClassifierAgent;
        this.codebaseInspector = codebaseInspector;
        this.requirementInterpreterAgent = requirementInterpreterAgent;
        this.plannerAgent = plannerAgent;
        this.workflowRepository = workflowRepository;
    }

    public WorkflowRun startWorkflow(String rawRequirement) {
        return startWorkflow(rawRequirement, null);
    }

    public WorkflowRun startWorkflow(String rawRequirement, String repositoryPath) {
        WorkflowRun run = new WorkflowRun(rawRequirement, repositoryPath);
        run.addEvent(WorkflowEvent.of(
                "WORKFLOW_INITIALIZED",
                WorkflowStage.INTAKE.name(),
                "Workflow instance initialized for requirement intake."
        ));

        return executePipeline(run, rawRequirement, repositoryPath);
    }

    public Optional<WorkflowRun> submitClarification(String workflowId, String clarificationText, String repositoryPath) {
        Optional<WorkflowRun> optRun = workflowRepository.findById(workflowId);
        if (optRun.isEmpty()) {
            return Optional.empty();
        }

        WorkflowRun run = optRun.get();
        if (run.getStatus() != WorkflowStatus.WAITING_FOR_CLARIFICATION) {
            throw new IllegalStateException("Workflow '" + workflowId + "' is not waiting for clarification. Current status: " + run.getStatus());
        }

        String effectiveRepoPath = (repositoryPath != null && !repositoryPath.isBlank())
                ? repositoryPath
                : run.getRepositoryPath();
        run.setRepositoryPath(effectiveRepoPath);

        run.addEvent(WorkflowEvent.of(
                "CLARIFICATION_SUBMITTED",
                run.getCurrentStage().name(),
                "User submitted clarification: " + clarificationText
        ));

        run.setUnansweredQuestions(List.of());

        String effectiveRequirement = run.getRequirement();
        if (clarificationText != null && !clarificationText.isBlank()) {
            if (run.getScenario() == Scenario.AMBIGUOUS) {
                effectiveRequirement = clarificationText;
            } else {
                effectiveRequirement = run.getRequirement() + " (" + clarificationText + ")";
            }
        }
        return Optional.of(executePipeline(run, effectiveRequirement, effectiveRepoPath));
    }

    private WorkflowRun executePipeline(WorkflowRun run, String requirementText, String repositoryPath) {
        // 1. Scenario Classification Stage
        run.transitionTo(WorkflowStatus.IN_PROGRESS, WorkflowStage.SCENARIO_CLASSIFICATION);
        run.addEvent(WorkflowEvent.of(
                "CLASSIFICATION_STARTED",
                WorkflowStage.SCENARIO_CLASSIFICATION.name(),
                "Initiating scenario classification (GREENFIELD, BROWNFIELD, AMBIGUOUS)."
        ));

        ScenarioClassificationResult classification = scenarioClassifierAgent.classify(requirementText, repositoryPath);
        run.setScenario(classification.scenario());

        AgentDecision classDecision = AgentDecision.of(
                ScenarioClassifierAgent.AGENT_NAME,
                classification.scenario().name(),
                classification.rationale(),
                classification.metadata()
        );
        run.addAgentDecision(classDecision);
        run.setClassificationDecision(classDecision);

        if (classification.fallbackOccurred()) {
            run.addEvent(WorkflowEvent.of(
                    "LLM_FALLBACK_TRIGGERED",
                    WorkflowStage.SCENARIO_CLASSIFICATION.name(),
                    "Model provider failed or returned invalid output; fell back to deterministic rules. Reason: " + classification.fallbackReason()
            ));
            run.addEvent(WorkflowEvent.of(
                    "SCENARIO_CLASSIFIED",
                    WorkflowStage.SCENARIO_CLASSIFICATION.name(),
                    "Scenario classified as " + classification.scenario() + " (fallback)."
            ));
        } else if ("MODEL_BACKED_AGENT".equals(classification.metadata().get("type"))) {
            run.addEvent(WorkflowEvent.of(
                    "MODEL_CLASSIFICATION_COMPLETED",
                    WorkflowStage.SCENARIO_CLASSIFICATION.name(),
                    "Model-backed scenario classification completed: " + classification.scenario() + "."
            ));
            run.addEvent(WorkflowEvent.of(
                    "SCENARIO_CLASSIFIED",
                    WorkflowStage.SCENARIO_CLASSIFICATION.name(),
                    "Scenario classified as " + classification.scenario() + " by model provider."
            ));
        } else {
            run.addEvent(WorkflowEvent.of(
                    "SCENARIO_CLASSIFIED",
                    WorkflowStage.SCENARIO_CLASSIFICATION.name(),
                    "Scenario classified as " + classification.scenario() + " via deterministic specialist."
            ));
        }

        // Handle AMBIGUOUS Scenario
        if (classification.scenario() == Scenario.AMBIGUOUS) {
            run.setUnansweredQuestions(classification.clarificationQuestions());
            run.addEvent(WorkflowEvent.of(
                    "AMBIGUITY_DETECTED",
                    WorkflowStage.SCENARIO_CLASSIFICATION.name(),
                    "Requirement lacks verifiable bounds; " + classification.clarificationQuestions().size() + " questions pending clarification."
            ));
            run.transitionTo(WorkflowStatus.WAITING_FOR_CLARIFICATION, WorkflowStage.SCENARIO_CLASSIFICATION);
            run.addEvent(WorkflowEvent.of(
                    "WORKFLOW_PAUSED",
                    WorkflowStage.SCENARIO_CLASSIFICATION.name(),
                    "Workflow paused in WAITING_FOR_CLARIFICATION stage. Awaiting clarification from user."
            ));
            return workflowRepository.save(run);
        }

        // 2. Codebase Inspection Stage (for Brownfield)
        if (classification.scenario() == Scenario.BROWNFIELD) {
            run.transitionTo(WorkflowStatus.IN_PROGRESS, WorkflowStage.CODEBASE_INSPECTION);
            run.addEvent(WorkflowEvent.of(
                    "INSPECTION_STARTED",
                    WorkflowStage.CODEBASE_INSPECTION.name(),
                    "Initiating safe, read-only codebase inspection for brownfield request."
            ));

            if (repositoryPath == null || repositoryPath.isBlank()) {
                run.setUnansweredQuestions(List.of(
                        "A repository path is required for brownfield changes to an existing codebase. Please provide the repository path."
                ));
                run.addEvent(WorkflowEvent.of(
                        "MISSING_REPOSITORY_PATH",
                        WorkflowStage.CODEBASE_INSPECTION.name(),
                        "Brownfield request lacks repository path; pausing for clarification."
                ));
                run.transitionTo(WorkflowStatus.WAITING_FOR_CLARIFICATION, WorkflowStage.CODEBASE_INSPECTION);
                run.addEvent(WorkflowEvent.of(
                        "WORKFLOW_PAUSED",
                        WorkflowStage.CODEBASE_INSPECTION.name(),
                        "Workflow paused awaiting repository path clarification."
                ));
                return workflowRepository.save(run);
            }

            try {
                RepositoryEvidence evidence = codebaseInspector.inspect(repositoryPath);
                run.setRepositoryEvidence(evidence);
                run.addEvent(WorkflowEvent.of(
                        "INSPECTION_COMPLETED",
                        WorkflowStage.CODEBASE_INSPECTION.name(),
                        "Codebase evidence recorded: " + evidence.totalFiles() + " files inspected (" +
                                (evidence.detectedLanguages().isEmpty() ? "none" : String.join(", ", evidence.detectedLanguages())) + ")."
                ));
            } catch (InspectionException e) {
                log.warn("Codebase inspection rejected: {}", e.getMessage());
                run.addEvent(WorkflowEvent.of(
                        "INSPECTION_FAILED",
                        WorkflowStage.CODEBASE_INSPECTION.name(),
                        "Codebase inspection failed or rejected: " + e.getMessage()
                ));
                run.transitionTo(WorkflowStatus.FAILED, WorkflowStage.CODEBASE_INSPECTION);
                return workflowRepository.save(run);
            }
        } else {
            // Greenfield: clear repository evidence
            run.setRepositoryEvidence(RepositoryEvidence.none());
        }

        // 3. Requirement Interpretation Stage
        run.transitionTo(WorkflowStatus.IN_PROGRESS, WorkflowStage.REQUIREMENT_INTERPRETATION);
        run.addEvent(WorkflowEvent.of(
                "INTERPRETATION_STARTED",
                WorkflowStage.REQUIREMENT_INTERPRETATION.name(),
                "Delegating requirement analysis to requirement interpreter agent."
        ));

        RequirementInterpretationResult interpretation = requirementInterpreterAgent.interpret(
                requirementText,
                run.getRepositoryEvidence()
        );

        run.addAgentDecision(AgentDecision.of(
                RequirementInterpreterAgent.AGENT_NAME,
                interpretation.decisionSummary(),
                interpretation.rationale(),
                interpretation.metadata()
        ));

        if (interpretation.fallbackOccurred()) {
            run.addEvent(WorkflowEvent.of(
                    "LLM_FALLBACK_TRIGGERED",
                    WorkflowStage.REQUIREMENT_INTERPRETATION.name(),
                    "Model provider failed or returned invalid output; fell back to deterministic rules. Reason: " + interpretation.fallbackReason()
            ));
        } else if ("MODEL_BACKED_AGENT".equals(interpretation.metadata().get("type"))) {
            run.addEvent(WorkflowEvent.of(
                    "MODEL_INTERPRETATION_COMPLETED",
                    WorkflowStage.REQUIREMENT_INTERPRETATION.name(),
                    "Model-backed interpretation succeeded using provider " + interpretation.metadata().get("provider") + " (" + interpretation.metadata().get("model") + ")."
            ));
        }

        run.setAssumptions(interpretation.assumptions());

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

        // 4. Task Planning Stage
        run.transitionTo(WorkflowStatus.IN_PROGRESS, WorkflowStage.TASK_PLANNING);
        run.addEvent(WorkflowEvent.of(
                "PLANNING_STARTED",
                WorkflowStage.TASK_PLANNING.name(),
                "Delegating task decomposition to dependency-aware planner agent."
        ));

        TaskPlanningResult planningResult = plannerAgent.plan(
                interpretation.acceptanceCriteria(),
                requirementText,
                run.getRepositoryEvidence()
        );

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
