package com.linkforge.service;

import com.linkforge.domain.workflow.WorkflowApproval;
import com.linkforge.domain.workflow.WorkflowClarification;
import com.linkforge.domain.workflow.WorkflowRun;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for WorkflowRun domain models with H2 JDBC persistence for
 * clarification history and human plan approval decisions.
 */
@Repository
public class WorkflowRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRepository.class);

    private final Map<String, WorkflowRun> storage = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;

    public WorkflowRepository() {
        this(null);
    }

    @Autowired
    public WorkflowRepository(@Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public WorkflowRun save(WorkflowRun run) {
        if (run == null) {
            throw new IllegalArgumentException("WorkflowRun cannot be null");
        }
        storage.put(run.getId(), run);

        if (jdbcTemplate != null) {
            try {
                // Persist any unpersisted clarifications
                for (WorkflowClarification clarification : run.getClarificationHistory()) {
                    persistClarificationIfAbsent(clarification);
                }

                // Persist approval if present
                if (run.getApproval() != null) {
                    persistOrUpdateApproval(run.getApproval());
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
        if (run == null) {
            return Optional.empty();
        }

        // If backed by JDBC, ensure clarifications and approval are synced
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
            } catch (Exception e) {
                log.warn("Error hydrating workflow {} from H2: {}", id, e.getMessage());
            }
        }

        return Optional.of(run);
    }

    public List<WorkflowRun> findAll() {
        return new ArrayList<>(storage.values());
    }

    public void clear() {
        storage.clear();
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update("DELETE FROM workflow_approvals");
                jdbcTemplate.update("DELETE FROM workflow_clarifications");
            } catch (Exception e) {
                log.warn("Error clearing H2 workflow tables: {}", e.getMessage());
            }
        }
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

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
