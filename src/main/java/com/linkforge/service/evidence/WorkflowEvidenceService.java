package com.linkforge.service.evidence;

import com.linkforge.api.dto.CriterionEvidenceItem;
import com.linkforge.api.dto.ExecutionVerificationStatus;
import com.linkforge.api.dto.SpecialistEvidenceDetail;
import com.linkforge.api.dto.TaskTraceabilityItem;
import com.linkforge.api.dto.WorkflowEvidenceResponse;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.specialist.SpecialistCriteriaMapper;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;
import org.springframework.stereotype.Service;

import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.GovernedExecutionRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service to derive structured workflow evidence, traceability matrices, and verification status.
 * Local filesystem paths and secrets are strictly excluded from responses.
 */
@Service
public class WorkflowEvidenceService {

    public WorkflowEvidenceResponse buildEvidenceResponse(WorkflowRun run) {
        if (run == null) {
            throw new IllegalArgumentException("WorkflowRun cannot be null");
        }

        List<CriterionEvidenceItem> criteriaEvidence = buildCriteriaEvidence(run);
        List<TaskTraceabilityItem> taskTraceability = buildTaskTraceability(run);
        ExecutionVerificationStatus verificationStatus = buildVerificationStatus(run);

        boolean planApproved = run.getApproval() != null && "APPROVED".equalsIgnoreCase(run.getApproval().decision());
        boolean codebaseEvidenceAvailable = run.getRepositoryEvidence() != null && run.getRepositoryEvidence().hasEvidence();

        boolean anyCriterionHasEventLink = criteriaEvidence.stream()
                .anyMatch(c -> !c.relevantEventTypes().isEmpty());
        String eventLinkageStatus = anyCriterionHasEventLink ? "LINKED" : "EVENT_LEVEL_LINKAGE_UNAVAILABLE";

        return new WorkflowEvidenceResponse(
                run.getId(),
                run.getScenario() != null ? run.getScenario().name() : null,
                run.getStatus().name(),
                run.getCurrentStage().name(),
                run.getCurrentPlanHash(),
                planApproved,
                codebaseEvidenceAvailable,
                criteriaEvidence,
                taskTraceability,
                verificationStatus,
                eventLinkageStatus,
                Instant.now()
        );
    }

    private List<CriterionEvidenceItem> buildCriteriaEvidence(WorkflowRun run) {
        List<String> rawCriteria = run.getAcceptanceCriteria();
        if (rawCriteria == null || rawCriteria.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<SpecialistInvocation>> criterionToInvocations = run.getSpecialistInvocations().stream()
                .flatMap(inv -> inv.addressedCriteria().stream().map(c -> Map.entry(c, inv)))
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        List<CriterionEvidenceItem> items = new ArrayList<>();
        for (int i = 0; i < rawCriteria.size(); i++) {
            String criterion = rawCriteria.get(i);
            String criterionId = SpecialistCriteriaMapper.extractOrAssignId(criterion, i);

            List<SpecialistInvocation> invocations = criterionToInvocations.getOrDefault(criterionId, List.of());
            List<SpecialistEvidenceDetail> findings = invocations.stream()
                    .map(inv -> new SpecialistEvidenceDetail(
                            inv.taskId(),
                            inv.agentName(),
                            inv.role(),
                            inv.status(),
                            inv.provider(),
                            inv.model(),
                            inv.fallbackOccurred(),
                            inv.fallbackReason(),
                            inv.recommendations(),
                            inv.testIdeas()
                    ))
                    .toList();

            List<String> specialistRoles = invocations.stream()
                    .map(SpecialistInvocation::role)
                    .distinct()
                    .toList();

            List<String> plannedTaskIds = run.getTasks().stream()
                    .filter(t -> matchesTask(t, criterionId, invocations))
                    .map(PlannedTask::taskId)
                    .distinct()
                    .toList();

            String status;
            if (!findings.isEmpty()) {
                boolean allSuccess = findings.stream().allMatch(f -> "SUCCESS".equalsIgnoreCase(f.status()));
                status = allSuccess ? "ANALYZED" : "PARTIALLY_ANALYZED";
            } else if (!plannedTaskIds.isEmpty()) {
                status = "PLANNED";
            } else {
                status = "UNCOVERED";
            }

            // Only link events that actually reference this specific criterion
            List<String> criterionEvents = run.getEvents().stream()
                    .filter(e -> (e.description() != null && e.description().contains(criterionId))
                            || (e.eventType() != null && e.eventType().contains(criterionId)))
                    .map(WorkflowEvent::eventType)
                    .distinct()
                    .toList();

            String eventLinkageStatus = criterionEvents.isEmpty()
                    ? "EVENT_LEVEL_LINKAGE_UNAVAILABLE"
                    : "LINKED";

            boolean hasPersistedEvidence = !findings.isEmpty();

            items.add(new CriterionEvidenceItem(
                    criterionId,
                    criterion,
                    status,
                    plannedTaskIds,
                    specialistRoles,
                    findings,
                    criterionEvents,
                    eventLinkageStatus,
                    hasPersistedEvidence
            ));
        }

        return items;
    }

    private boolean matchesTask(PlannedTask task, String criterionId, List<SpecialistInvocation> invocations) {
        boolean directMatch = invocations.stream().anyMatch(inv -> inv.taskId().equals(task.taskId()));
        if (directMatch) return true;
        if (task.description() != null && task.description().contains(criterionId)) return true;
        return task.title() != null && task.title().contains(criterionId);
    }

    private List<TaskTraceabilityItem> buildTaskTraceability(WorkflowRun run) {
        List<PlannedTask> tasks = run.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, SpecialistInvocation> invocationByTask = run.getSpecialistInvocations().stream()
                .collect(Collectors.toMap(SpecialistInvocation::taskId, inv -> inv, (first, second) -> second));

        List<TaskTraceabilityItem> items = new ArrayList<>();
        for (PlannedTask task : tasks) {
            SpecialistInvocation inv = invocationByTask.get(task.taskId());
            boolean executed = inv != null;
            String agentName = inv != null ? inv.agentName() : null;
            String specialistStatus = inv != null ? inv.status() : null;
            List<String> addressedCriteria = inv != null ? inv.addressedCriteria() : List.of();

            items.add(new TaskTraceabilityItem(
                    task.taskId(),
                    task.title(),
                    task.status(),
                    task.specialistRole(),
                    task.dependencies(),
                    addressedCriteria,
                    executed,
                    agentName,
                    specialistStatus
            ));
        }

        return items;
    }

    private ExecutionVerificationStatus buildVerificationStatus(WorkflowRun run) {
        String reqAnalysis = run.getAcceptanceCriteria().isEmpty() ? "PENDING" : "COMPLETED";
        String scenarioClass = run.getScenario() != null ? run.getScenario().name() : "PENDING";

        String codebaseInspection;
        if (run.getScenario() == Scenario.BROWNFIELD) {
            codebaseInspection = (run.getRepositoryEvidence() != null && run.getRepositoryEvidence().hasEvidence())
                    ? "COMPLETED" : "FAILED";
        } else if (run.getScenario() == Scenario.GREENFIELD) {
            codebaseInspection = "NOT_APPLICABLE (GREENFIELD)";
        } else {
            codebaseInspection = "PENDING";
        }

        String taskPlanning = run.getTasks().isEmpty() ? "PENDING" : "COMPLETED";

        String humanApproval;
        if (run.getApproval() != null) {
            humanApproval = run.getApproval().decision();
        } else if (run.getStatus() == WorkflowStatus.WAITING_FOR_APPROVAL) {
            humanApproval = "AWAITING_APPROVAL";
        } else {
            humanApproval = "PENDING";
        }

        String specialistAnalysis;
        if (run.getStatus() == WorkflowStatus.COMPLETED) {
            specialistAnalysis = "COMPLETED";
        } else if (run.getStatus() == WorkflowStatus.CANCELLED) {
            specialistAnalysis = "HALTED_BY_CANCELLATION";
        } else if (run.getStatus() == WorkflowStatus.FAILED) {
            specialistAnalysis = "FAILED";
        } else if (run.getSpecialistInvocations().isEmpty()) {
            specialistAnalysis = "PENDING";
        } else {
            boolean allSuccess = run.getSpecialistInvocations().stream()
                    .allMatch(inv -> "SUCCESS".equalsIgnoreCase(inv.status()));
            specialistAnalysis = allSuccess ? "COMPLETED" : "PARTIAL";
        }

        String sourceCodeGeneration;
        String buildExecution;
        String automatedTestExecution;

        GovernedExecutionRecord execRecord = run.getExecutionRecord();
        if (execRecord == null) {
            sourceCodeGeneration = "NOT_SUPPORTED";
            buildExecution = "NOT_SUPPORTED";
            automatedTestExecution = "UNVERIFIED";
        } else {
            String execStatus = execRecord.status() != null ? execRecord.status().toUpperCase(Locale.ROOT) : "";
            BuildValidationResult buildResult = execRecord.buildValidation();
            boolean isTestCommand = buildResult != null && buildResult.command() != null && buildResult.command().contains("test");
            boolean testSucceeded = "COMPLETED".equals(execStatus) && buildResult != null && buildResult.isSuccess();

            switch (execStatus) {
                case "COMPLETED" -> {
                    sourceCodeGeneration = "VERIFIED (ISOLATED_PROPOSAL)";
                    buildExecution = "VERIFIED (MAVEN_WRAPPER_BUILD)";
                    automatedTestExecution = (testSucceeded && isTestCommand)
                            ? "VERIFIED (TARGETED_TEST_EXECUTION)"
                            : "UNVERIFIED";
                }
                case "TIMED_OUT" -> {
                    sourceCodeGeneration = "FAILED (TIMED_OUT)";
                    buildExecution = "TIMED_OUT";
                    automatedTestExecution = "FAILED (TIMED_OUT)";
                }
                case "ROLLED_BACK" -> {
                    sourceCodeGeneration = "ROLLED_BACK (VERIFIED_RESTORATION)";
                    buildExecution = "FAILED (ROLLED_BACK)";
                    automatedTestExecution = "FAILED (ROLLED_BACK)";
                }
                case "BLOCKED" -> {
                    sourceCodeGeneration = "BLOCKED";
                    buildExecution = "BLOCKED";
                    automatedTestExecution = "BLOCKED";
                }
                case "FAILED" -> {
                    sourceCodeGeneration = "FAILED";
                    buildExecution = "FAILED";
                    automatedTestExecution = "FAILED";
                }
                default -> {
                    sourceCodeGeneration = "NOT_SUPPORTED";
                    buildExecution = "NOT_SUPPORTED";
                    automatedTestExecution = "UNVERIFIED";
                }
            }
        }

        return new ExecutionVerificationStatus(
                reqAnalysis,
                scenarioClass,
                codebaseInspection,
                taskPlanning,
                humanApproval,
                specialistAnalysis,
                sourceCodeGeneration,
                buildExecution,
                automatedTestExecution,
                "NOT_SUPPORTED"   // deploymentAndRelease: no deployment/release performed
        );
    }
}
