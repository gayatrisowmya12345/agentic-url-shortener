package com.linkforge.domain.workflow;

import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.Scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class WorkflowRun {

    private final String id;
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

    private final List<WorkflowEvent> events = new ArrayList<>();
    private final List<AgentDecision> agentDecisions = new ArrayList<>();

    public WorkflowRun(String requirement) {
        this(requirement, null);
    }

    public WorkflowRun(String requirement, String repositoryPath) {
        this.id = UUID.randomUUID().toString();
        this.requirement = requirement;
        this.repositoryPath = repositoryPath;
        this.status = WorkflowStatus.CREATED;
        this.currentStage = WorkflowStage.INTAKE;
        this.createdAt = Instant.now();
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
        this.updatedAt = Instant.now();
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
        return classificationDecision;
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
}
