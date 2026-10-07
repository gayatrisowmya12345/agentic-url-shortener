package com.linkforge.service.release;

import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.exception.WorkflowNotFoundException;
import com.linkforge.domain.workflow.implementation.TestReportItem;
import com.linkforge.domain.workflow.release.CriterionReadinessItem;
import com.linkforge.domain.workflow.release.ReleaseApprovalRecord;
import com.linkforge.domain.workflow.release.ReleaseReadinessOutcome;
import com.linkforge.domain.workflow.specialist.SpecialistCriteriaMapper;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.evidence.WorkflowEvidenceService;
import com.linkforge.service.implementation.GovernedPatchApplier;
import com.linkforge.service.security.InvalidPlanHashException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service calculating release readiness outcomes and enforcing the human approval gate.
 * Deployment itself is explicitly NOT_SUPPORTED.
 */
@Service
public class ReleaseReadinessService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseReadinessService.class);

    private final WorkflowRepository workflowRepository;

    @Autowired
    public ReleaseReadinessService(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    public ReleaseReadinessOutcome evaluateReleaseReadiness(WorkflowRun run) {
        if (run == null) {
            throw new IllegalArgumentException("WorkflowRun cannot be null.");
        }

        List<CriterionReadinessItem> criteriaItems = new ArrayList<>();
        List<String> criteria = run.getAcceptanceCriteria();
        List<TestReportItem> testReports = List.of();
        if (run.getExecutionRecord() != null && run.getExecutionRecord().buildValidation() != null) {
            testReports = run.getExecutionRecord().buildValidation().testReports();
            if (testReports == null) {
                testReports = List.of();
            }
        }

        for (int i = 0; i < criteria.size(); i++) {
            String critText = criteria.get(i);
            String critId = SpecialistCriteriaMapper.extractOrAssignId(critText, i);

            List<String> passingTests = testReports.stream()
                    .filter(t -> t.isPassed() && WorkflowEvidenceService.testCoversCriterion(t, critId))
                    .map(t -> t.testSuite() + "#" + t.testCase())
                    .toList();

            boolean covered = !passingTests.isEmpty();
            criteriaItems.add(new CriterionReadinessItem(
                    critId,
                    critText,
                    covered,
                    passingTests,
                    covered ? "VERIFIED" : "UNVERIFIED"
            ));
        }

        boolean hasExecution = run.getExecutionRecord() != null;
        boolean isBuildSuccess = hasExecution
                && run.getExecutionRecord().buildValidation() != null
                && run.getExecutionRecord().buildValidation().isSuccess();

        int totalFailed = (int) testReports.stream().filter(TestReportItem::isFailed).count();
        int totalPassed = (int) testReports.stream().filter(TestReportItem::isPassed).count();

        List<String> missingCriteria = criteriaItems.stream()
                .filter(c -> !c.coveredByPassingTest())
                .map(CriterionReadinessItem::criterionId)
                .toList();

        boolean isRolledBack = run.getStatus() == WorkflowStatus.ROLLED_BACK
                || (hasExecution && run.getExecutionRecord().rollback() != null);
        boolean isBlocked = run.getStatus() == WorkflowStatus.BLOCKED
                || (hasExecution && "BLOCKED".equalsIgnoreCase(run.getExecutionRecord().status()));
        boolean changeApproved = run.getApproval() != null
                && "APPROVED".equalsIgnoreCase(run.getApproval().decision());

        List<String> unresolvedRisks = new ArrayList<>(run.getUnansweredQuestions());

        boolean ready = isBuildSuccess
                && totalFailed == 0
                && missingCriteria.isEmpty()
                && !isRolledBack
                && !isBlocked
                && changeApproved
                && !criteria.isEmpty();

        String status;
        if (isRolledBack) {
            status = "ROLLED_BACK";
        } else if (isBlocked) {
            status = "BLOCKED";
        } else if (ready) {
            status = "READY";
        } else {
            status = "NOT_READY";
        }

        String buildStatus = hasExecution && run.getExecutionRecord().buildValidation() != null
                ? run.getExecutionRecord().buildValidation().status()
                : "NOT_EXECUTED";
        String rollbackStatus = isRolledBack ? "ROLLED_BACK" : "NONE";

        String outcomeHash = computeOutcomeHash(
                run.getId(),
                criteriaItems,
                run.getApproval() != null ? run.getApproval().planHash() : "",
                buildStatus,
                totalPassed,
                totalFailed,
                missingCriteria,
                unresolvedRisks,
                rollbackStatus
        );

        ReleaseApprovalRecord currentApproval = run.getReleaseReadiness() != null
                ? run.getReleaseReadiness().approval()
                : null;

        ReleaseReadinessOutcome outcome = new ReleaseReadinessOutcome(
                outcomeHash,
                status,
                ready,
                criteriaItems,
                totalPassed,
                totalFailed,
                missingCriteria,
                unresolvedRisks,
                rollbackStatus,
                buildStatus,
                "NOT_SUPPORTED",
                Instant.now(),
                currentApproval
        );

        run.setReleaseReadiness(outcome);
        return outcome;
    }

    public WorkflowRun approveReleaseReadiness(
            String workflowId,
            String outcomeHash,
            String decision,
            String approver,
            String comments
    ) {
        WorkflowRun run = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new WorkflowNotFoundException("Workflow '" + workflowId + "' was not found."));

        ReleaseReadinessOutcome outcome = run.getReleaseReadiness();
        if (outcome == null) {
            outcome = evaluateReleaseReadiness(run);
        }

        if (outcomeHash == null || !outcomeHash.trim().equals(outcome.outcomeHash())) {
            throw new InvalidPlanHashException("Submitted release outcome hash '" + (outcomeHash != null ? outcomeHash.trim() : "") +
                    "' does not match current outcome hash '" + outcome.outcomeHash() + "'. Hash tampering detected.");
        }

        String effectiveApprover = (approver != null && !approver.isBlank()) ? approver.trim() : "authorized-approver";
        String normalizedDecision = (decision != null) ? decision.trim().toUpperCase() : "REJECTED";

        if ("REJECTED".equals(normalizedDecision)) {
            ReleaseApprovalRecord approval = new ReleaseApprovalRecord(
                    "REJECTED",
                    effectiveApprover,
                    outcomeHash.trim(),
                    Instant.now(),
                    comments
            );
            run.setReleaseReadiness(outcome.withApproval(approval, "REJECTED"));
            run.addEvent(WorkflowEvent.of(
                    "RELEASE_REJECTED",
                    run.getCurrentStage().name(),
                    "Release readiness rejected by " + effectiveApprover + (comments != null && !comments.isBlank() ? ": " + comments : "")
            ));
            return workflowRepository.save(run);
        }

        // Validate approval prerequisites
        if (run.getStatus() == WorkflowStatus.BLOCKED || (run.getExecutionRecord() != null && "BLOCKED".equalsIgnoreCase(run.getExecutionRecord().status()))) {
            throw new IllegalStateException("Cannot approve release readiness: workflow execution is blocked.");
        }
        if (run.getStatus() == WorkflowStatus.ROLLED_BACK || (run.getExecutionRecord() != null && run.getExecutionRecord().rollback() != null)) {
            throw new IllegalStateException("Cannot approve release readiness: workflow has been rolled back.");
        }
        if (run.getExecutionRecord() == null || run.getExecutionRecord().buildValidation() == null) {
            throw new IllegalStateException("Cannot approve release readiness: build validation evidence is incomplete.");
        }
        if (!run.getExecutionRecord().buildValidation().isSuccess()) {
            throw new IllegalStateException("Cannot approve release readiness: build validation failed.");
        }
        if (outcome.totalTestsFailed() > 0) {
            throw new IllegalStateException("Cannot approve release readiness: " + outcome.totalTestsFailed() + " tests failed.");
        }
        if (!outcome.missingCriteriaCoverage().isEmpty()) {
            throw new IllegalStateException("Cannot approve release readiness: missing passing test evidence for required criteria: " + outcome.missingCriteriaCoverage());
        }
        if (!outcome.readyForRelease()) {
            throw new IllegalStateException("Cannot approve release readiness: release readiness criteria are not satisfied.");
        }

        // Approval granted
        ReleaseApprovalRecord approval = new ReleaseApprovalRecord(
                "APPROVED",
                effectiveApprover,
                outcomeHash.trim(),
                Instant.now(),
                comments
        );
        run.setReleaseReadiness(outcome.withApproval(approval, "APPROVED"));
        run.addEvent(WorkflowEvent.of(
                "RELEASE_APPROVED",
                run.getCurrentStage().name(),
                "Release readiness approved for outcome hash " + outcomeHash.trim() + " by " + effectiveApprover +
                        ". Actual deployment remains NOT_SUPPORTED."
        ));
        return workflowRepository.save(run);
    }

    private String computeOutcomeHash(
            String workflowId,
            List<CriterionReadinessItem> criteria,
            String changeApprovalHash,
            String buildStatus,
            int totalPassed,
            int totalFailed,
            List<String> missingCriteria,
            List<String> unresolvedRisks,
            String rollbackStatus
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(workflowId).append("|");
        for (var c : criteria) {
            sb.append(c.criterionId()).append(":").append(c.coveredByPassingTest()).append(";");
        }
        sb.append("|approval:").append(changeApprovalHash);
        sb.append("|build:").append(buildStatus);
        sb.append("|passed:").append(totalPassed);
        sb.append("|failed:").append(totalFailed);
        sb.append("|missing:").append(String.join(",", missingCriteria));
        sb.append("|risks:").append(String.join(",", unresolvedRisks));
        sb.append("|rollback:").append(rollbackStatus);

        return GovernedPatchApplier.computeSha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
