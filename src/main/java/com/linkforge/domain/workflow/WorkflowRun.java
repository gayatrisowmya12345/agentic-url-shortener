package com.linkforge.domain.workflow;

import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class WorkflowRun {

    private final String id;
    private final String originalRequirement;
    private final String requirement;
    private String repositoryPath;
    private Scenario scenario;
    private RepositoryEvidence repositoryEvidence;
    private AgentDecision classificationDecision;

    private WorkflowStatus status;
    private WorkflowStage currentStage;
    private final Instant createdAt;
    private Instant updatedAt;

    private List<String> acceptanceCriteria = Collections.emptyList();
    private List<String> assumptions = Collections.emptyList();
    private List<String> unansweredQuestions = Collections.emptyList();
    private List<PlannedTask> tasks = Collections.emptyList();
    private String currentPlanHash;

    private final List<WorkflowEvent> events = new ArrayList<>();
    private final List<AgentDecision> agentDecisions = new ArrayList<>();
    private final List<SpecialistInvocation> specialistInvocations = new ArrayList<>();
    private final List<WorkflowClarification> clarificationHistory = new ArrayList<>();
    private WorkflowApproval approval;
    private WorkflowCancellation cancellation;

    public WorkflowRun(String requirement) {
        this(requirement, null);
    }

    public WorkflowRun(String requirement, String repositoryPath) {
        this(UUID.randomUUID().toString(), requirement, requirement, repositoryPath, Instant.now());
    }

    public WorkflowRun(
            String id,
            String originalRequirement,
            String requirement,
            String repositoryPath,
            Instant createdAt
    ) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.originalRequirement = originalRequirement;
        this.requirement = requirement;
        this.repositoryPath = repositoryPath;
        this.status = WorkflowStatus.CREATED;
        this.currentStage = WorkflowStage.INTAKE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = this.createdAt;
    }

    public synchronized void transitionTo(WorkflowStatus newStatus, WorkflowStage newStage) {
        this.status = newStatus;
        this.currentStage = newStage;
        this.updatedAt = Instant.now();
    }

    public synchronized void addEvent(WorkflowEvent event) {
        this.events.add(event);
        this.updatedAt = Instant.now();
    }

    public synchronized void addAgentDecision(AgentDecision decision) {
        this.agentDecisions.add(decision);
        this.updatedAt = Instant.now();
    }

    public synchronized void setScenario(Scenario scenario) {
        this.scenario = scenario;
        this.updatedAt = Instant.now();
    }

    public synchronized void setRepositoryPath(String repositoryPath) {
        this.repositoryPath = repositoryPath;
        this.updatedAt = Instant.now();
    }

    public synchronized void setRepositoryEvidence(RepositoryEvidence repositoryEvidence) {
        this.repositoryEvidence = repositoryEvidence;
        this.updatedAt = Instant.now();
    }

    public synchronized void setClassificationDecision(AgentDecision classificationDecision) {
        this.classificationDecision = classificationDecision;
        this.updatedAt = Instant.now();
    }

    public synchronized void setAcceptanceCriteria(List<String> criteria) {
        this.acceptanceCriteria = criteria != null ? List.copyOf(criteria) : Collections.emptyList();
        this.updatedAt = Instant.now();
    }

    public synchronized void setAssumptions(List<String> assumptions) {
        this.assumptions = assumptions != null ? List.copyOf(assumptions) : Collections.emptyList();
        this.updatedAt = Instant.now();
    }

    public synchronized void setUnansweredQuestions(List<String> questions) {
        this.unansweredQuestions = questions != null ? List.copyOf(questions) : Collections.emptyList();
        this.updatedAt = Instant.now();
    }

    public synchronized void setTasks(List<PlannedTask> tasks) {
        this.tasks = tasks != null ? List.copyOf(tasks) : Collections.emptyList();
        String newHash = PlanHasher.computePlanHash(this.tasks);
        if (this.currentPlanHash != null && !this.currentPlanHash.equals(newHash)) {
            if (this.approval != null && !this.approval.planHash().equals(newHash)) {
                this.approval = null; // Invalidate approval if plan hash changes
            }
        }
        this.currentPlanHash = newHash;
        this.updatedAt = Instant.now();
    }

    public synchronized void addClarification(WorkflowClarification clarification) {
        if (clarification != null) {
            this.clarificationHistory.add(clarification);
            this.updatedAt = Instant.now();
        }
    }

    public synchronized void setClarificationHistory(List<WorkflowClarification> clarifications) {
        this.clarificationHistory.clear();
        if (clarifications != null) {
            this.clarificationHistory.addAll(clarifications);
        }
        this.updatedAt = Instant.now();
    }

    public synchronized List<WorkflowClarification> getClarificationHistory() {
        return Collections.unmodifiableList(new ArrayList<>(clarificationHistory));
    }

    public synchronized void setApproval(WorkflowApproval approval) {
        this.approval = approval;
        this.updatedAt = Instant.now();
    }

    public synchronized WorkflowApproval getApproval() {
        return approval;
    }

    public synchronized void setCancellation(WorkflowCancellation cancellation) {
        this.cancellation = cancellation;
        this.updatedAt = Instant.now();
    }

    public synchronized WorkflowCancellation getCancellation() {
        return cancellation;
    }

    public synchronized boolean isCancelled() {
        return this.status == WorkflowStatus.CANCELLED;
    }

    public synchronized void cancel(WorkflowCancellation cancellation) {
        this.status = WorkflowStatus.CANCELLED;
        this.cancellation = cancellation;
        this.updatedAt = Instant.now();
    }

    public synchronized String getCurrentPlanHash() {
        return currentPlanHash;
    }

    public synchronized void setCurrentPlanHash(String hash) {
        this.currentPlanHash = hash;
        this.updatedAt = Instant.now();
    }

    public String getOriginalRequirement() {
        return originalRequirement;
    }

    public String getId() {
        return id;
    }

    public String getRequirement() {
        return requirement;
    }

    public synchronized String getRepositoryPath() {
        return repositoryPath;
    }

    public synchronized Scenario getScenario() {
        return scenario;
    }

    public synchronized RepositoryEvidence getRepositoryEvidence() {
        return repositoryEvidence;
    }

    public synchronized AgentDecision getClassificationDecision() {
        if (classificationDecision != null) {
            return classificationDecision;
        }
        return agentDecisions.stream()
                .filter(d -> "scenario-classifier".equals(d.agentName()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public synchronized WorkflowStatus getStatus() {
        return status;
    }

    public synchronized WorkflowStage getCurrentStage() {
        return currentStage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized Instant getUpdatedAt() {
        return updatedAt;
    }

    public synchronized List<String> getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public synchronized List<String> getAssumptions() {
        return assumptions;
    }

    public synchronized List<String> getUnansweredQuestions() {
        return unansweredQuestions;
    }

    public synchronized List<PlannedTask> getTasks() {
        return tasks;
    }

    public synchronized List<WorkflowEvent> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized List<AgentDecision> getAgentDecisions() {
        return Collections.unmodifiableList(new ArrayList<>(agentDecisions));
    }

    public synchronized void addSpecialistInvocation(SpecialistInvocation invocation) {
        if (invocation != null) {
            this.specialistInvocations.add(invocation);
            this.updatedAt = Instant.now();
        }
    }

    public synchronized void setSpecialistInvocations(List<SpecialistInvocation> invocations) {
        this.specialistInvocations.clear();
        if (invocations != null) {
            this.specialistInvocations.addAll(invocations);
        }
        this.updatedAt = Instant.now();
    }

    public synchronized List<SpecialistInvocation> getSpecialistInvocations() {
        return Collections.unmodifiableList(new ArrayList<>(specialistInvocations));
    }

    public synchronized void setStatus(WorkflowStatus status) {
        this.status = status;
    }

    public synchronized void setCurrentStage(WorkflowStage currentStage) {
        this.currentStage = currentStage;
    }

    public synchronized void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public synchronized void setEvents(List<WorkflowEvent> events) {
        this.events.clear();
        if (events != null) {
            this.events.addAll(events);
        }
    }

    public synchronized void setAgentDecisions(List<AgentDecision> agentDecisions) {
        this.agentDecisions.clear();
        if (agentDecisions != null) {
            this.agentDecisions.addAll(agentDecisions);
        }
    }
}
