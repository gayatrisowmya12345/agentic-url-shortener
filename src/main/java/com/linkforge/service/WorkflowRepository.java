package com.linkforge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linkforge.domain.workflow.AgentDecision;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowCancellation;
import com.linkforge.domain.workflow.WorkflowClarification;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for WorkflowRun domain models with H2 JDBC persistence for
 * workflow runs, ordered event history, agent decisions, clarification history,
 * plan approvals, and safe stop cancellations.
 */
@Repository
public class WorkflowRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRepository.class);

    private final Map<String, WorkflowRun> storage = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkflowRepository() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public WorkflowRepository(
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public WorkflowRun save(WorkflowRun run) {
        if (run == null) {
            throw new IllegalArgumentException("WorkflowRun cannot be null");
        }
        storage.put(run.getId(), run);

        if (jdbcTemplate != null) {
            try {
                // Persist run state
                persistOrUpdateWorkflowRun(run);

                // Persist any unpersisted events
                persistEvents(run);

                // Persist any unpersisted agent decisions
                persistAgentDecisions(run);

                // Persist any unpersisted clarifications
                for (WorkflowClarification clarification : run.getClarificationHistory()) {
                    persistClarificationIfAbsent(clarification);
                }

                // Persist approval if present
                if (run.getApproval() != null) {
                    persistOrUpdateApproval(run.getApproval());
                }

                // Persist cancellation if present
                if (run.getCancellation() != null) {
                    persistOrUpdateCancellation(run.getCancellation());
                }
            } catch (Exception e) {
                log.error("Failed to persist workflow metadata to H2 for workflow {}: {}", run.getId(), e.getMessage(), e);
            }
        }

        return run;
    }

    public Optional<WorkflowRun> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        WorkflowRun run = storage.get(id);
        if (run == null && jdbcTemplate != null) {
            run = loadWorkflowRunFromDb(id).orElse(null);
            if (run != null) {
                storage.put(run.getId(), run);
            }
        }
        if (run == null) {
            return Optional.empty();
        }

        // If backed by JDBC, ensure sub-entities are synced
        if (jdbcTemplate != null) {
            try {
                List<WorkflowClarification> clarifications = findClarificationsByWorkflowId(id);
                if (!clarifications.isEmpty() && run.getClarificationHistory().isEmpty()) {
                    run.setClarificationHistory(clarifications);
                }

                Optional<WorkflowApproval> approvalOpt = findApprovalByWorkflowId(id);
                if (approvalOpt.isPresent() && run.getApproval() == null) {
                    run.setApproval(approvalOpt.get());
                }

                Optional<WorkflowCancellation> cancellationOpt = findCancellationByWorkflowId(id);
                if (cancellationOpt.isPresent() && run.getCancellation() == null) {
                    run.setCancellation(cancellationOpt.get());
                }
            } catch (Exception e) {
                log.warn("Error hydrating workflow {} from H2: {}", id, e.getMessage());
            }
        }

        return Optional.of(run);
    }

    public List<WorkflowRun> findAll() {
        if (jdbcTemplate != null) {
            try {
                List<String> ids = jdbcTemplate.query(
                        "SELECT id FROM workflow_runs ORDER BY created_at ASC",
                        (rs, rowNum) -> rs.getString("id")
                );
                List<WorkflowRun> runs = new ArrayList<>();
                for (String id : ids) {
                    findById(id).ifPresent(runs::add);
                }
                if (!runs.isEmpty()) {
                    return runs;
                }
            } catch (Exception e) {
                log.warn("Failed to query workflow runs from DB: {}", e.getMessage());
            }
        }
        return new ArrayList<>(storage.values());
    }

    public void clear() {
        storage.clear();
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update("DELETE FROM workflow_agent_decisions");
                jdbcTemplate.update("DELETE FROM workflow_events");
                jdbcTemplate.update("DELETE FROM workflow_cancellations");
                jdbcTemplate.update("DELETE FROM workflow_approvals");
                jdbcTemplate.update("DELETE FROM workflow_clarifications");
                jdbcTemplate.update("DELETE FROM workflow_runs");
            } catch (Exception e) {
                log.warn("Error clearing H2 workflow tables: {}", e.getMessage());
            }
        }
    }

    public void clearMemoryCache() {
        storage.clear();
    }

    public List<WorkflowEvent> findEventsByWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return List.of();
        }
        if (jdbcTemplate != null) {
            String sql = "SELECT id, workflow_id, event_type, stage, description, timestamp FROM workflow_events WHERE workflow_id = ? ORDER BY timestamp ASC";
            try {
                return jdbcTemplate.query(sql, new EventRowMapper(), workflowId);
            } catch (Exception e) {
                log.warn("Failed to query events for workflow {}: {}", workflowId, e.getMessage());
            }
        }
        WorkflowRun run = storage.get(workflowId);
        return run != null ? run.getEvents() : List.of();
    }

    public List<AgentDecision> findAgentDecisionsByWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return List.of();
        }
        if (jdbcTemplate != null) {
            String sql = "SELECT id, workflow_id, agent_name, agent_type, decision, rationale, metadata_json, timestamp FROM workflow_agent_decisions WHERE workflow_id = ? ORDER BY timestamp ASC";
            try {
                return jdbcTemplate.query(sql, new AgentDecisionRowMapper(), workflowId);
            } catch (Exception e) {
                log.warn("Failed to query agent decisions for workflow {}: {}", workflowId, e.getMessage());
            }
        }
        WorkflowRun run = storage.get(workflowId);
        return run != null ? run.getAgentDecisions() : List.of();
    }

    public void saveClarification(WorkflowClarification clarification) {
        if (clarification == null) {
            return;
        }
        WorkflowRun run = storage.get(clarification.workflowId());
        if (run != null) {
            boolean alreadyPresent = run.getClarificationHistory().stream()
                    .anyMatch(c -> c.id().equals(clarification.id()));
            if (!alreadyPresent) {
                run.addClarification(clarification);
            }
        }

        if (jdbcTemplate != null) {
            persistClarificationIfAbsent(clarification);
        }
    }

    public List<WorkflowClarification> findClarificationsByWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return List.of();
        }
        if (jdbcTemplate == null) {
            WorkflowRun run = storage.get(workflowId);
            return run != null ? run.getClarificationHistory() : List.of();
        }

        String sql = "SELECT id, workflow_id, clarification_text, submitted_by, submitted_at FROM workflow_clarifications WHERE workflow_id = ? ORDER BY submitted_at ASC";
        try {
            return jdbcTemplate.query(sql, new ClarificationRowMapper(), workflowId);
        } catch (Exception e) {
            log.warn("Failed to query clarifications for workflow {}: {}", workflowId, e.getMessage());
            WorkflowRun run = storage.get(workflowId);
            return run != null ? run.getClarificationHistory() : List.of();
        }
    }

    public void saveApproval(WorkflowApproval approval) {
        if (approval == null) {
            return;
        }
        WorkflowRun run = storage.get(approval.workflowId());
        if (run != null) {
            run.setApproval(approval);
        }

        if (jdbcTemplate != null) {
            persistOrUpdateApproval(approval);
        }
    }

    public Optional<WorkflowApproval> findApprovalByWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        if (jdbcTemplate == null) {
            WorkflowRun run = storage.get(workflowId);
            return run != null ? Optional.ofNullable(run.getApproval()) : Optional.empty();
        }

        String sql = "SELECT id, workflow_id, decision, approver, plan_hash, comments, decided_at FROM workflow_approvals WHERE workflow_id = ?";
        try {
            WorkflowApproval approval = jdbcTemplate.queryForObject(sql, new ApprovalRowMapper(), workflowId);
            return Optional.ofNullable(approval);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to query approval for workflow {}: {}", workflowId, e.getMessage());
            WorkflowRun run = storage.get(workflowId);
            return run != null ? Optional.ofNullable(run.getApproval()) : Optional.empty();
        }
    }

    public void saveCancellation(WorkflowCancellation cancellation) {
        if (cancellation == null) {
            return;
        }
        WorkflowRun run = storage.get(cancellation.workflowId());
        if (run != null) {
            run.setCancellation(cancellation);
        }

        if (jdbcTemplate != null) {
            persistOrUpdateCancellation(cancellation);
        }
    }

    public Optional<WorkflowCancellation> findCancellationByWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        if (jdbcTemplate == null) {
            WorkflowRun run = storage.get(workflowId);
            return run != null ? Optional.ofNullable(run.getCancellation()) : Optional.empty();
        }

        String sql = "SELECT id, workflow_id, cancelled_by, reason, cancelled_at FROM workflow_cancellations WHERE workflow_id = ?";
        try {
            WorkflowCancellation cancellation = jdbcTemplate.queryForObject(sql, new CancellationRowMapper(), workflowId);
            return Optional.ofNullable(cancellation);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to query cancellation for workflow {}: {}", workflowId, e.getMessage());
            WorkflowRun run = storage.get(workflowId);
            return run != null ? Optional.ofNullable(run.getCancellation()) : Optional.empty();
        }
    }

    private void persistOrUpdateWorkflowRun(WorkflowRun run) {
        if (jdbcTemplate == null || run == null) return;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE id = ?",
                Integer.class,
                run.getId()
        );

        String criteriaJson = toJson(run.getAcceptanceCriteria());
        String assumptionsJson = toJson(run.getAssumptions());
        String questionsJson = toJson(run.getUnansweredQuestions());
        String tasksJson = toJson(run.getTasks());
        String invocationsJson = toJson(run.getSpecialistInvocations());
        String repoEvidenceJson = toJson(run.getRepositoryEvidence());
        String proposalJson = toJson(run.getImplementationProposal());
        String executionRecordJson = toJson(run.getExecutionRecord());

        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE workflow_runs SET requirement = ?, repository_path = ?, scenario = ?, status = ?, current_stage = ?, plan_hash = ?, acceptance_criteria_json = ?, assumptions_json = ?, unanswered_questions_json = ?, tasks_json = ?, specialist_invocations_json = ?, repository_evidence_json = ?, implementation_proposal_json = ?, execution_record_json = ?, updated_at = ? WHERE id = ?",
                    run.getRequirement(),
                    run.getRepositoryPath(),
                    run.getScenario() != null ? run.getScenario().name() : null,
                    run.getStatus().name(),
                    run.getCurrentStage().name(),
                    run.getCurrentPlanHash(),
                    criteriaJson,
                    assumptionsJson,
                    questionsJson,
                    tasksJson,
                    invocationsJson,
                    repoEvidenceJson,
                    proposalJson,
                    executionRecordJson,
                    Timestamp.from(run.getUpdatedAt()),
                    run.getId()
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO workflow_runs (id, original_requirement, requirement, repository_path, scenario, status, current_stage, plan_hash, acceptance_criteria_json, assumptions_json, unanswered_questions_json, tasks_json, specialist_invocations_json, repository_evidence_json, implementation_proposal_json, execution_record_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    run.getId(),
                    run.getOriginalRequirement(),
                    run.getRequirement(),
                    run.getRepositoryPath(),
                    run.getScenario() != null ? run.getScenario().name() : null,
                    run.getStatus().name(),
                    run.getCurrentStage().name(),
                    run.getCurrentPlanHash(),
                    criteriaJson,
                    assumptionsJson,
                    questionsJson,
                    tasksJson,
                    invocationsJson,
                    repoEvidenceJson,
                    proposalJson,
                    executionRecordJson,
                    Timestamp.from(run.getCreatedAt()),
                    Timestamp.from(run.getUpdatedAt())
            );
        }
    }

    private void persistEvents(WorkflowRun run) {
        if (jdbcTemplate == null || run == null) return;
        for (WorkflowEvent event : run.getEvents()) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_events WHERE id = ?",
                    Integer.class,
                    event.eventId()
            );
            if (count == null || count == 0) {
                jdbcTemplate.update(
                        "INSERT INTO workflow_events (id, workflow_id, event_type, stage, description, timestamp) VALUES (?, ?, ?, ?, ?, ?)",
                        event.eventId(),
                        run.getId(),
                        event.eventType(),
                        event.stage(),
                        event.description(),
                        Timestamp.from(event.timestamp())
                );
            }
        }
    }

    private void persistAgentDecisions(WorkflowRun run) {
        if (jdbcTemplate == null || run == null) return;
        for (AgentDecision decision : run.getAgentDecisions()) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_agent_decisions WHERE id = ?",
                    Integer.class,
                    decision.decisionId()
            );
            if (count == null || count == 0) {
                jdbcTemplate.update(
                        "INSERT INTO workflow_agent_decisions (id, workflow_id, agent_name, agent_type, decision, rationale, metadata_json, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        decision.decisionId(),
                        run.getId(),
                        decision.agentName(),
                        decision.agentType(),
                        decision.decision(),
                        decision.rationale(),
                        toJson(decision.metadata()),
                        Timestamp.from(decision.timestamp())
                );
            }
        }
    }

    private Optional<WorkflowRun> loadWorkflowRunFromDb(String id) {
        String sql = "SELECT * FROM workflow_runs WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                String originalReq = rs.getString("original_requirement");
                String req = rs.getString("requirement");
                String repoPath = rs.getString("repository_path");
                Instant createdAt = toInstant(rs.getTimestamp("created_at"));
                Instant updatedAt = toInstant(rs.getTimestamp("updated_at"));

                WorkflowRun run = new WorkflowRun(id, originalReq, req, repoPath, createdAt);
                run.setUpdatedAt(updatedAt);

                String statusStr = rs.getString("status");
                if (statusStr != null) {
                    run.setStatus(WorkflowStatus.valueOf(statusStr));
                }
                String stageStr = rs.getString("current_stage");
                if (stageStr != null) {
                    run.setCurrentStage(WorkflowStage.valueOf(stageStr));
                }
                String scenarioStr = rs.getString("scenario");
                if (scenarioStr != null) {
                    run.setScenario(Scenario.valueOf(scenarioStr));
                }
                String planHash = rs.getString("plan_hash");
                if (planHash != null) {
                    run.setCurrentPlanHash(planHash);
                }

                String criteriaJson = rs.getString("acceptance_criteria_json");
                if (criteriaJson != null && !criteriaJson.isBlank()) {
                    List<String> criteria = deserialize(criteriaJson, new TypeReference<List<String>>() {});
                    if (criteria != null) run.setAcceptanceCriteria(criteria);
                }
                String assumptionsJson = rs.getString("assumptions_json");
                if (assumptionsJson != null && !assumptionsJson.isBlank()) {
                    List<String> assumptions = deserialize(assumptionsJson, new TypeReference<List<String>>() {});
                    if (assumptions != null) run.setAssumptions(assumptions);
                }
                String questionsJson = rs.getString("unanswered_questions_json");
                if (questionsJson != null && !questionsJson.isBlank()) {
                    List<String> questions = deserialize(questionsJson, new TypeReference<List<String>>() {});
                    if (questions != null) run.setUnansweredQuestions(questions);
                }
                String tasksJson = rs.getString("tasks_json");
                if (tasksJson != null && !tasksJson.isBlank()) {
                    List<PlannedTask> tasks = deserialize(tasksJson, new TypeReference<List<PlannedTask>>() {});
                    if (tasks != null) run.setTasks(tasks);
                }
                String invocationsJson = rs.getString("specialist_invocations_json");
                if (invocationsJson != null && !invocationsJson.isBlank()) {
                    List<SpecialistInvocation> invocations = deserialize(invocationsJson, new TypeReference<List<SpecialistInvocation>>() {});
                    if (invocations != null) run.setSpecialistInvocations(invocations);
                }
                String repoEvidenceJson = rs.getString("repository_evidence_json");
                if (repoEvidenceJson != null && !repoEvidenceJson.isBlank()) {
                    try {
                        run.setRepositoryEvidence(objectMapper.readValue(repoEvidenceJson, RepositoryEvidence.class));
                    } catch (Exception ignored) {}
                }
                String proposalJson = rs.getString("implementation_proposal_json");
                if (proposalJson != null && !proposalJson.isBlank()) {
                    try {
                        run.setImplementationProposal(objectMapper.readValue(proposalJson, ImplementationProposal.class));
                    } catch (Exception ignored) {}
                }
                String execJson = rs.getString("execution_record_json");
                if (execJson != null && !execJson.isBlank()) {
                    try {
                        run.setExecutionRecord(objectMapper.readValue(execJson, GovernedExecutionRecord.class));
                    } catch (Exception ignored) {}
                }

                // Load events
                run.setEvents(findEventsByWorkflowId(id));

                // Load agent decisions
                run.setAgentDecisions(findAgentDecisionsByWorkflowId(id));

                // Load clarifications
                run.setClarificationHistory(findClarificationsByWorkflowId(id));

                // Load approval
                findApprovalByWorkflowId(id).ifPresent(run::setApproval);

                // Load cancellation
                findCancellationByWorkflowId(id).ifPresent(run::setCancellation);

                return run;
            }, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Error loading workflow {} from DB: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private void persistClarificationIfAbsent(WorkflowClarification clarification) {
        if (jdbcTemplate == null || clarification == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_clarifications WHERE id = ?",
                Integer.class,
                clarification.id()
        );
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO workflow_clarifications (id, workflow_id, clarification_text, submitted_by, submitted_at) VALUES (?, ?, ?, ?, ?)",
                    clarification.id(),
                    clarification.workflowId(),
                    clarification.clarificationText(),
                    clarification.submittedBy(),
                    Timestamp.from(clarification.submittedAt())
            );
        }
    }

    private void persistOrUpdateApproval(WorkflowApproval approval) {
        if (jdbcTemplate == null || approval == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_approvals WHERE workflow_id = ?",
                Integer.class,
                approval.workflowId()
        );
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE workflow_approvals SET decision = ?, approver = ?, plan_hash = ?, comments = ?, decided_at = ? WHERE workflow_id = ?",
                    approval.decision(),
                    approval.approver(),
                    approval.planHash(),
                    approval.comments(),
                    Timestamp.from(approval.decidedAt()),
                    approval.workflowId()
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO workflow_approvals (id, workflow_id, decision, approver, plan_hash, comments, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    approval.id(),
                    approval.workflowId(),
                    approval.decision(),
                    approval.approver(),
                    approval.planHash(),
                    approval.comments(),
                    Timestamp.from(approval.decidedAt())
            );
        }
    }

    private void persistOrUpdateCancellation(WorkflowCancellation cancellation) {
        if (jdbcTemplate == null || cancellation == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_cancellations WHERE id = ? OR workflow_id = ?",
                Integer.class,
                cancellation.id(),
                cancellation.workflowId()
        );
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE workflow_cancellations SET cancelled_by = ?, reason = ?, cancelled_at = ? WHERE workflow_id = ?",
                    cancellation.cancelledBy(),
                    cancellation.reason(),
                    Timestamp.from(cancellation.cancelledAt()),
                    cancellation.workflowId()
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO workflow_cancellations (id, workflow_id, cancelled_by, reason, cancelled_at) VALUES (?, ?, ?, ?, ?)",
                    cancellation.id(),
                    cancellation.workflowId(),
                    cancellation.cancelledBy(),
                    cancellation.reason(),
                    Timestamp.from(cancellation.cancelledAt())
            );
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return null;
        }
    }

    private <T> T deserialize(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON: {}", e.getMessage());
            return null;
        }
    }

    private static class EventRowMapper implements RowMapper<WorkflowEvent> {
        @Override
        public WorkflowEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WorkflowEvent(
                    rs.getString("id"),
                    rs.getString("event_type"),
                    rs.getString("stage"),
                    rs.getString("description"),
                    toInstant(rs.getTimestamp("timestamp"))
            );
        }
    }

    private class AgentDecisionRowMapper implements RowMapper<AgentDecision> {
        @Override
        public AgentDecision mapRow(ResultSet rs, int rowNum) throws SQLException {
            String metaJson = rs.getString("metadata_json");
            Map<String, Object> metadata = Collections.emptyMap();
            if (metaJson != null && !metaJson.isBlank()) {
                try {
                    metadata = objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.debug("Failed to deserialize agent decision metadata: {}", e.getMessage());
                }
            }
            return new AgentDecision(
                    rs.getString("id"),
                    rs.getString("agent_name"),
                    rs.getString("agent_type"),
                    rs.getString("decision"),
                    rs.getString("rationale"),
                    metadata,
                    toInstant(rs.getTimestamp("timestamp"))
            );
        }
    }

    private static class ClarificationRowMapper implements RowMapper<WorkflowClarification> {
        @Override
        public WorkflowClarification mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WorkflowClarification(
                    rs.getString("id"),
                    rs.getString("workflow_id"),
                    rs.getString("clarification_text"),
                    rs.getString("submitted_by"),
                    toInstant(rs.getTimestamp("submitted_at"))
            );
        }
    }

    private static class ApprovalRowMapper implements RowMapper<WorkflowApproval> {
        @Override
        public WorkflowApproval mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WorkflowApproval(
                    rs.getString("id"),
                    rs.getString("workflow_id"),
                    rs.getString("decision"),
                    rs.getString("approver"),
                    rs.getString("plan_hash"),
                    toInstant(rs.getTimestamp("decided_at")),
                    rs.getString("comments")
            );
        }
    }

    private static class CancellationRowMapper implements RowMapper<WorkflowCancellation> {
        @Override
        public WorkflowCancellation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WorkflowCancellation(
                    rs.getString("id"),
                    rs.getString("workflow_id"),
                    rs.getString("cancelled_by"),
                    rs.getString("reason"),
                    toInstant(rs.getTimestamp("cancelled_at"))
            );
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
