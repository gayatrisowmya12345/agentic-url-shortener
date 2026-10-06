package com.linkforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.agent.DependencyAwarePlannerAgent;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.agent.RequirementInterpretationResult;
import com.linkforge.agent.ScenarioClassifierAgent;
import com.linkforge.agent.TaskPlanningResult;
import com.linkforge.agent.specialist.ApiBehaviorSpecialistAgent;
import com.linkforge.agent.specialist.DataPersistenceSpecialistAgent;
import com.linkforge.agent.specialist.SecurityValidationSpecialistAgent;
import com.linkforge.agent.specialist.SpecialistAgent;
import com.linkforge.agent.specialist.SpecialistRegistry;
import com.linkforge.agent.specialist.TestingQualitySpecialistAgent;
import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowClarification;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.scenario.ScenarioClassificationResult;
import com.linkforge.domain.workflow.scenario.exception.InspectionException;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;
import com.linkforge.service.coordination.CoordinationResult;
import com.linkforge.service.coordination.SpecialistCoordinationProperties;
import com.linkforge.service.coordination.TaskGraphCoordinator;
import com.linkforge.domain.workflow.specialist.exception.TaskGraphException;
import com.linkforge.service.coordination.TaskGraphValidator;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.inspection.CodebaseInspector;
import com.linkforge.service.security.InvalidPlanHashException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service orchestrating the complete software delivery workflow:
 * 1. Intake
 * 2. Scenario Classification (GREENFIELD, BROWNFIELD, AMBIGUOUS)
 * 3. Codebase Inspection (for brownfield)
 * 4. Requirement Interpretation
 * 5. Task Planning
 * 6. Human Plan Approval Gate (pauses for authorized human approval)
 * 7. Bounded Specialist Coordination
 * 8. Finished
 */
@Service
public class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private final ScenarioClassifierAgent scenarioClassifierAgent;
    private final CodebaseInspector codebaseInspector;
    private final RequirementInterpreterAgent requirementInterpreterAgent;
    private final DependencyAwarePlannerAgent plannerAgent;
    private final WorkflowRepository workflowRepository;
    private final TaskGraphCoordinator taskGraphCoordinator;

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
                workflowRepository,
                createDefaultTaskGraphCoordinator()
        );
    }

    public WorkflowOrchestrator(
            ScenarioClassifierAgent scenarioClassifierAgent,
            CodebaseInspector codebaseInspector,
            RequirementInterpreterAgent requirementInterpreterAgent,
            DependencyAwarePlannerAgent plannerAgent,
            WorkflowRepository workflowRepository
    ) {
        this(
                scenarioClassifierAgent,
                codebaseInspector,
                requirementInterpreterAgent,
                plannerAgent,
                workflowRepository,
                createDefaultTaskGraphCoordinator()
        );
    }

    @Autowired
    public WorkflowOrchestrator(
            ScenarioClassifierAgent scenarioClassifierAgent,
            CodebaseInspector codebaseInspector,
            RequirementInterpreterAgent requirementInterpreterAgent,
            DependencyAwarePlannerAgent plannerAgent,
            WorkflowRepository workflowRepository,
            TaskGraphCoordinator taskGraphCoordinator
    ) {
        this.scenarioClassifierAgent = scenarioClassifierAgent;
        this.codebaseInspector = codebaseInspector;
        this.requirementInterpreterAgent = requirementInterpreterAgent;
        this.plannerAgent = plannerAgent;
        this.workflowRepository = workflowRepository;
        this.taskGraphCoordinator = taskGraphCoordinator != null ? taskGraphCoordinator : createDefaultTaskGraphCoordinator();
    }

    private static TaskGraphCoordinator createDefaultTaskGraphCoordinator() {
        ObjectMapper mapper = new ObjectMapper();
        List<SpecialistAgent> agents = List.of(
                new ApiBehaviorSpecialistAgent(null, mapper),
                new DataPersistenceSpecialistAgent(null, mapper),
                new SecurityValidationSpecialistAgent(null, mapper),
                new TestingQualitySpecialistAgent(null, mapper)
        );
        SpecialistRegistry registry = new SpecialistRegistry(agents);
        return new TaskGraphCoordinator(new TaskGraphValidator(), registry, new SpecialistCoordinationProperties());
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
        return submitClarification(workflowId, clarificationText, repositoryPath, "operator");
    }

    public Optional<WorkflowRun> submitClarification(String workflowId, String clarificationText, String repositoryPath, String submittedBy) {
        if (clarificationText == null || clarificationText.trim().isEmpty()) {
            throw new IllegalArgumentException("Clarification text cannot be blank or incomplete.");
        }

        Optional<WorkflowRun> optRun = workflowRepository.findById(workflowId);
        if (optRun.isEmpty()) {
            return Optional.empty();
        }

        WorkflowRun run = optRun.get();

        String trimmedClarification = clarificationText.trim();

        // Idempotency check for repeat submissions of the same clarification
        if (!run.getClarificationHistory().isEmpty()) {
            WorkflowClarification last = run.getClarificationHistory().get(run.getClarificationHistory().size() - 1);
            if (last.clarificationText().trim().equalsIgnoreCase(trimmedClarification)) {
                return Optional.of(run);
            }
        }

        if (run.getStatus() != WorkflowStatus.WAITING_FOR_CLARIFICATION) {
            throw new IllegalStateException("Workflow '" + workflowId + "' is not waiting for clarification. Current status: " + run.getStatus());
        }

        String effectiveRepoPath = (repositoryPath != null && !repositoryPath.isBlank())
                ? repositoryPath
                : run.getRepositoryPath();
        run.setRepositoryPath(effectiveRepoPath);

        String effectiveActor = (submittedBy != null && !submittedBy.isBlank()) ? submittedBy.trim() : "operator";
        WorkflowClarification clarification = WorkflowClarification.of(run.getId(), clarificationText.trim(), effectiveActor);
        run.addClarification(clarification);
        workflowRepository.saveClarification(clarification);

        run.addEvent(WorkflowEvent.of(
                "CLARIFICATION_SUBMITTED",
                run.getCurrentStage().name(),
                "Clarification submitted by " + effectiveActor + ": " + clarificationText.trim()
        ));

        run.setUnansweredQuestions(List.of());

        // Re-run requirement analysis using original requirement plus clarification history
        StringBuilder combined = new StringBuilder(run.getOriginalRequirement());
        for (WorkflowClarification c : run.getClarificationHistory()) {
            combined.append(". Clarification: ").append(c.clarificationText());
        }
        String effectiveRequirement = combined.toString();

        return Optional.of(executePipeline(run, effectiveRequirement, effectiveRepoPath));
    }

    public Optional<WorkflowRun> approvePlan(
            String workflowId,
            String decision,
            String planHash,
            String approver,
            String comments
    ) {
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("Decision cannot be blank. Must be APPROVED or REJECTED.");
        }
        String normalizedDecision = decision.trim().toUpperCase();
        if (!"APPROVED".equals(normalizedDecision) && !"REJECTED".equals(normalizedDecision)) {
            throw new IllegalArgumentException("Invalid decision '" + decision + "'. Must be APPROVED or REJECTED.");
        }
        if (planHash == null || planHash.isBlank()) {
            throw new IllegalArgumentException("Plan hash cannot be blank.");
        }

        Optional<WorkflowRun> optRun = workflowRepository.findById(workflowId);
        if (optRun.isEmpty()) {
            return Optional.empty();
        }

        WorkflowRun run = optRun.get();

        // Idempotency: duplicate approval or rejection with identical hash and decision
        if (run.getApproval() != null
                && planHash.trim().equals(run.getApproval().planHash())
                && normalizedDecision.equalsIgnoreCase(run.getApproval().decision())) {
            return Optional.of(run);
        }

        if (run.getStatus() != WorkflowStatus.WAITING_FOR_APPROVAL) {
            throw new IllegalStateException("Workflow '" + workflowId + "' is not waiting for approval. Current status: " + run.getStatus());
        }

        if (run.getCurrentPlanHash() == null || !run.getCurrentPlanHash().equals(planHash.trim())) {
            throw new InvalidPlanHashException("Submitted plan hash '" + planHash.trim() +
                    "' does not match current plan hash '" + run.getCurrentPlanHash() + "'.");
        }

        String effectiveApprover = (approver != null && !approver.isBlank()) ? approver.trim() : "authorized-approver";
        WorkflowApproval approval = WorkflowApproval.of(run.getId(), normalizedDecision, effectiveApprover, planHash.trim(), comments);
        run.setApproval(approval);
        workflowRepository.saveApproval(approval);

        if ("REJECTED".equals(normalizedDecision)) {
            run.transitionTo(WorkflowStatus.REJECTED, WorkflowStage.PLAN_APPROVAL);
            run.addEvent(WorkflowEvent.of(
                    "PLAN_REJECTED",
                    WorkflowStage.PLAN_APPROVAL.name(),
                    "Plan hash " + planHash.trim() + " rejected by " + effectiveApprover +
                            (comments != null && !comments.isBlank() ? ": " + comments : "")
            ));
            return Optional.of(workflowRepository.save(run));
        }

        // APPROVED: proceed to specialist coordination
        run.addEvent(WorkflowEvent.of(
                "PLAN_APPROVED",
                WorkflowStage.PLAN_APPROVAL.name(),
                "Plan hash " + planHash.trim() + " approved by " + effectiveApprover +
                        (comments != null && !comments.isBlank() ? ": " + comments : "")
        ));

        WorkflowRun coordinated = executeSpecialistCoordination(run, run.getRequirement());
        return Optional.of(workflowRepository.save(coordinated));
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
                "Generated dependency graph with " + planningResult.tasks().size() + " tasks (plan hash: " + run.getCurrentPlanHash() + ")."
        ));

        // 5. Human Plan Approval Gate: Pause before specialist coordination
        boolean isApproved = run.getApproval() != null
                && run.getApproval().isApproved()
                && run.getCurrentPlanHash() != null
                && run.getCurrentPlanHash().equals(run.getApproval().planHash());

        if (!isApproved) {
            run.transitionTo(WorkflowStatus.WAITING_FOR_APPROVAL, WorkflowStage.PLAN_APPROVAL);
            run.addEvent(WorkflowEvent.of(
                    "AWAITING_PLAN_APPROVAL",
                    WorkflowStage.PLAN_APPROVAL.name(),
                    "Workflow paused awaiting human plan approval for plan hash: " + run.getCurrentPlanHash()
            ));
            return workflowRepository.save(run);
        }

        // 6. Specialist Coordination Stage (only if already approved)
        return executeSpecialistCoordination(run, requirementText);
    }

    public WorkflowRun executeSpecialistCoordination(WorkflowRun run, String requirementText) {
        run.transitionTo(WorkflowStatus.IN_PROGRESS, WorkflowStage.SPECIALIST_COORDINATION);
        run.addEvent(WorkflowEvent.of(
                "COORDINATION_STARTED",
                WorkflowStage.SPECIALIST_COORDINATION.name(),
                "Initiating bounded specialist coordination across " + run.getTasks().size() + " planned tasks."
        ));

        CoordinationResult coordinationResult;
        try {
            coordinationResult = taskGraphCoordinator.coordinate(
                    run.getTasks(),
                    requirementText,
                    run.getAcceptanceCriteria(),
                    run.getScenario(),
                    run.getRepositoryEvidence()
            );

            run.setTasks(coordinationResult.updatedTasks());
            run.setSpecialistInvocations(coordinationResult.invocations());

            for (SpecialistInvocation inv : coordinationResult.invocations()) {
                if ("SUCCESS".equalsIgnoreCase(inv.status())) {
                    run.addEvent(WorkflowEvent.of(
                            "SPECIALIST_TASK_COMPLETED",
                            WorkflowStage.SPECIALIST_COORDINATION.name(),
                            "Specialist " + inv.agentName() + " (" + inv.role() + ") completed task " + inv.taskId() + "."
                    ));
                    if (inv.fallbackOccurred()) {
                        run.addEvent(WorkflowEvent.of(
                                "SPECIALIST_FALLBACK_TRIGGERED",
                                WorkflowStage.SPECIALIST_COORDINATION.name(),
                                "Specialist " + inv.agentName() + " used fallback for task " + inv.taskId() + ": " + inv.fallbackReason()
                        ));
                    }
                } else {
                    run.addEvent(WorkflowEvent.of(
                            "SPECIALIST_TASK_FAILED",
                            WorkflowStage.SPECIALIST_COORDINATION.name(),
                            "Specialist " + inv.agentName() + " failed on task " + inv.taskId() + ": " + inv.outputSummary()
                    ));
                }
            }

            if (!coordinationResult.allSuccessful()) {
                run.addEvent(WorkflowEvent.of(
                        "COORDINATION_PARTIAL_FAILURE",
                        WorkflowStage.SPECIALIST_COORDINATION.name(),
                        "Coordination halted with " + coordinationResult.failedCount() + " failures and " +
                                coordinationResult.skippedCount() + " skipped tasks. Retained " +
                                coordinationResult.completedCount() + " successful task results."
                ));
                run.transitionTo(WorkflowStatus.FAILED, WorkflowStage.SPECIALIST_COORDINATION);
                return workflowRepository.save(run);
            }

            run.addEvent(WorkflowEvent.of(
                    "COORDINATION_COMPLETED",
                    WorkflowStage.SPECIALIST_COORDINATION.name(),
                    "Successfully coordinated and executed " + coordinationResult.completedCount() + " specialist tasks."
            ));
        } catch (TaskGraphException e) {
            log.warn("Task graph validation failed: {}", e.getMessage());
            run.addEvent(WorkflowEvent.of(
                    "COORDINATION_FAILED",
                    WorkflowStage.SPECIALIST_COORDINATION.name(),
                    "Task graph validation rejected: " + e.getMessage()
            ));
            run.transitionTo(WorkflowStatus.FAILED, WorkflowStage.SPECIALIST_COORDINATION);
            return workflowRepository.save(run);
        } catch (Exception e) {
            log.warn("Specialist coordination failed: {}", e.getMessage());
            run.addEvent(WorkflowEvent.of(
                    "COORDINATION_FAILED",
                    WorkflowStage.SPECIALIST_COORDINATION.name(),
                    "Specialist coordination error: " + e.getMessage()
            ));
            run.transitionTo(WorkflowStatus.FAILED, WorkflowStage.SPECIALIST_COORDINATION);
            return workflowRepository.save(run);
        }

        // Transition to Finished & Completed
        run.transitionTo(WorkflowStatus.COMPLETED, WorkflowStage.FINISHED);
        run.addEvent(WorkflowEvent.of(
                "WORKFLOW_COMPLETED",
                WorkflowStage.FINISHED.name(),
                "Agentic workflow vertical slice completed successfully with specialist coordination."
        ));

        return workflowRepository.save(run);
    }

    public Optional<WorkflowRun> getWorkflowRun(String id) {
        return workflowRepository.findById(id);
    }
}
