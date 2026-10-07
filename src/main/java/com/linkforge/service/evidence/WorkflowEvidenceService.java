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
import java.util.HashMap;
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
                Instant.now(),
                run.getReleaseReadiness()
        );
    }

    private List<CriterionEvidenceItem> buildCriteriaEvidence(WorkflowRun run) {
        List<String> rawCriteria = run.getAcceptanceCriteria();
        if (rawCriteria == null || rawCriteria.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<SpecialistInvocation>> criterionToInvocations = new HashMap<>();
        for (SpecialistInvocation inv : run.getSpecialistInvocations()) {
            if (inv.addressedCriteria() != null) {
                for (String c : inv.addressedCriteria()) {
                    if (c == null || c.isBlank()) continue;
                    for (String part : c.split("[,;\\s]+")) {
                        if (!part.isBlank()) {
                            criterionToInvocations.computeIfAbsent(normalizeCriterionId(part), k -> new ArrayList<>()).add(inv);
                        }
                    }
                }
            }
        }

        List<CriterionEvidenceItem> items = new ArrayList<>();
        for (int i = 0; i < rawCriteria.size(); i++) {
            String criterion = rawCriteria.get(i);
            String criterionId = SpecialistCriteriaMapper.extractOrAssignId(criterion, i);

            List<SpecialistInvocation> invocations = criterionToInvocations.getOrDefault(normalizeCriterionId(criterionId), List.of());
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

            // Only link events that actually reference this specific criterion as an exact token
            List<String> criterionEvents = run.getEvents().stream()
                    .filter(e -> textContainsCriterionToken(e.description(), criterionId)
                            || textContainsCriterionToken(e.eventType(), criterionId))
                    .map(WorkflowEvent::eventType)
                    .distinct()
                    .toList();

            String eventLinkageStatus = criterionEvents.isEmpty()
                    ? "EVENT_LEVEL_LINKAGE_UNAVAILABLE"
                    : "LINKED";

            String productionPath = null;
            String testPath = null;
            if (run.getImplementationProposal() != null) {
                for (var change : run.getImplementationProposal().changes()) {
                    if (change.criterionLineage() != null && criterionLineageMatches(change.criterionLineage(), criterionId)) {
                        if (change.path().contains("src/test/")) {
                            testPath = change.path();
                        } else {
                            productionPath = change.path();
                        }
                    }
                }
            }

            boolean hasPersistedEvidence = !findings.isEmpty();

            String validationStatus = "UNVERIFIED";
            if (run.getExecutionRecord() != null && run.getExecutionRecord().buildValidation() != null) {
                var bVal = run.getExecutionRecord().buildValidation();
                boolean critHasPassingTest = bVal.testReports() != null && bVal.testReports().stream()
                        .anyMatch(t -> t.isPassed() && testCoversCriterion(t, criterionId));
                boolean critHasFailedTest = bVal.testReports() != null && bVal.testReports().stream()
                        .anyMatch(t -> t.isFailed() && testCoversCriterion(t, criterionId));
                if (critHasPassingTest && !critHasFailedTest) {
                    validationStatus = "PASSED";
                } else if (critHasFailedTest) {
                    validationStatus = "FAILED";
                } else {
                    validationStatus = "UNVERIFIED";
                }
            }

            items.add(new CriterionEvidenceItem(
                    criterionId,
                    criterion,
                    status,
                    plannedTaskIds,
                    specialistRoles,
                    findings,
                    criterionEvents,
                    eventLinkageStatus,
                    hasPersistedEvidence,
                    productionPath,
                    testPath,
                    validationStatus
            ));
        }

        return items;
    }

    private boolean matchesTask(PlannedTask task, String criterionId, List<SpecialistInvocation> invocations) {
        boolean directMatch = invocations.stream().anyMatch(inv -> inv.taskId().equals(task.taskId()));
        if (directMatch) return true;
        if (textContainsCriterionToken(task.description(), criterionId)) return true;
        return textContainsCriterionToken(task.title(), criterionId);
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

        GovernedExecutionRecord execRecord = run.getExecutionRecord();
        String scenarioClass;
        if (execRecord != null && execRecord.executionType() != null) {
            scenarioClass = execRecord.executionType();
        } else if (run.getScenario() != null) {
            scenarioClass = run.getScenario().name();
        } else {
            scenarioClass = "PENDING";
        }

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

        if (execRecord == null) {
            sourceCodeGeneration = "NOT_SUPPORTED";
            buildExecution = "NOT_SUPPORTED";
            automatedTestExecution = "UNVERIFIED";
        } else {
            String execStatus = execRecord.status() != null ? execRecord.status().toUpperCase(Locale.ROOT) : "";
            BuildValidationResult buildResult = execRecord.buildValidation();
            boolean isTestCommand = buildResult != null && buildResult.command() != null
                    && (buildResult.command().contains("test") || buildResult.command().contains("verify"));

            boolean hasFailedTests = buildResult != null && (!buildResult.isSuccess() || buildResult.exitCode() != 0
                    || buildResult.failedTests() > 0);

            java.util.Set<String> coveredCriteria = getCoveredCriteria(run);

            switch (execStatus) {
                case "COMPLETED" -> {
                    sourceCodeGeneration = "VERIFIED (ISOLATED_PROPOSAL)";
                    buildExecution = "VERIFIED (MAVEN_WRAPPER_BUILD)";

                    if (buildResult == null) {
                        automatedTestExecution = "UNVERIFIED";
                    } else if (hasFailedTests) {
                        automatedTestExecution = "UNVERIFIED (TESTS_FAILED)";
                    } else if (!buildResult.isSuccess()) {
                        automatedTestExecution = "UNVERIFIED";
                    } else if (buildResult.totalTests() == 0 || buildResult.testReports() == null || buildResult.testReports().isEmpty()) {
                        automatedTestExecution = "UNVERIFIED (ZERO_RELEVANT_TESTS)";
                    } else if (!coveredCriteria.isEmpty()) {
                        java.util.Set<String> satisfiedCriteria = new java.util.LinkedHashSet<>();
                        for (String critId : coveredCriteria) {
                            boolean hasPassingTest = buildResult.testReports().stream()
                                    .anyMatch(t -> t.isPassed() && testCoversCriterion(t, critId));
                            if (hasPassingTest) {
                                satisfiedCriteria.add(critId);
                            }
                        }

                        if (satisfiedCriteria.isEmpty()) {
                            automatedTestExecution = "UNVERIFIED (ZERO_RELEVANT_TESTS)";
                        } else if (satisfiedCriteria.size() < coveredCriteria.size()) {
                            automatedTestExecution = "UNVERIFIED (MISSING_CRITERION_COVERAGE)";
                        } else {
                            automatedTestExecution = (isTestCommand && buildResult.fullVerification())
                                    ? "VERIFIED (FULL_VERIFICATION)"
                                    : "VERIFIED (TARGETED_TEST_EXECUTION)";
                        }
                    } else {
                        boolean anyPassingRelevant = buildResult.testReports().stream()
                                .anyMatch(t -> t.isPassed() && isRelevantTest(t, run.getImplementationProposal(), run.getAcceptanceCriteria()));
                        if (anyPassingRelevant && isTestCommand) {
                            automatedTestExecution = buildResult.fullVerification()
                                    ? "VERIFIED (FULL_VERIFICATION)"
                                    : "VERIFIED (TARGETED_TEST_EXECUTION)";
                        } else if (!anyPassingRelevant) {
                            automatedTestExecution = "UNVERIFIED (ZERO_RELEVANT_TESTS)";
                        } else {
                            automatedTestExecution = "UNVERIFIED";
                        }
                    }
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

    public static java.util.Set<String> getCoveredCriteria(WorkflowRun run) {
        java.util.Set<String> covered = new java.util.LinkedHashSet<>();
        var proposal = run.getImplementationProposal();
        if (proposal == null && run.getExecutionRecord() != null) {
            proposal = run.getExecutionRecord().proposal();
        }
        if (proposal != null && proposal.changes() != null) {
            for (var change : proposal.changes()) {
                if (change.criterionLineage() != null && !change.criterionLineage().isBlank()) {
                    for (String part : change.criterionLineage().split("[,;\\s]+")) {
                        if (!part.isBlank()) {
                            covered.add(part.trim());
                        }
                    }
                }
            }
        }
        return covered;
    }

    public static String normalizeCriterionId(String criterionId) {
        if (criterionId == null) {
            return "";
        }
        return criterionId.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean criterionLineageMatches(String lineage, String criterionId) {
        if (lineage == null || criterionId == null || criterionId.isBlank()) {
            return false;
        }
        String normalizedCrit = normalizeCriterionId(criterionId);
        for (String part : lineage.split("[,;\\s]+")) {
            if (part.isBlank()) continue;
            if (normalizeCriterionId(part).equals(normalizedCrit)) {
                return true;
            }
        }
        return false;
    }

    public static boolean textContainsCriterionToken(String text, String criterionId) {
        if (text == null || criterionId == null || criterionId.isBlank()) {
            return false;
        }
        String normalizedTarget = normalizeCriterionId(criterionId);
        if (normalizedTarget.isEmpty()) {
            return false;
        }
        // Split text on whitespace, commas, semicolons, colons, quotes, brackets, parens, slashes, or underscores.
        // Hyphens are preserved as part of criterion IDs (e.g. AC-1, AC-10).
        String[] rawTokens = text.split("[\\s,;:()\\[\\]\"'{}`/\\\\_]+");
        for (String rawToken : rawTokens) {
            if (rawToken.isBlank()) continue;
            // Strip leading or trailing punctuation except hyphens (e.g., "AC-1.", "#AC-1", "!AC-1")
            String token = rawToken.replaceAll("^[\\p{Punct}&&[^-]]+|[\\p{Punct}&&[^-]]+$", "");
            if (normalizeCriterionId(token).equals(normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    public static boolean testCoversCriterion(
            com.linkforge.domain.workflow.implementation.TestReportItem test,
            String criterionId
    ) {
        if (test == null || criterionId == null || criterionId.isBlank()) {
            return false;
        }
        String normalizedCrit = normalizeCriterionId(criterionId);
        if (test.criterionLineage() != null) {
            for (String lineage : test.criterionLineage()) {
                if (lineage == null || lineage.isBlank()) continue;
                for (String part : lineage.split("[,;\\s]+")) {
                    if (part.isBlank()) continue;
                    if (normalizeCriterionId(part).equals(normalizedCrit)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isRelevantTest(
            com.linkforge.domain.workflow.implementation.TestReportItem test,
            com.linkforge.domain.workflow.implementation.ImplementationProposal proposal,
            List<String> criteria
    ) {
        if (test == null) return false;
        if (test.criterionLineage() != null && !test.criterionLineage().isEmpty()) {
            if (criteria != null && !criteria.isEmpty()) {
                for (String c : criteria) {
                    if (testCoversCriterion(test, c)) {
                        return true;
                    }
                }
            } else {
                return true;
            }
        }
        if (proposal != null && proposal.changes() != null) {
            for (var change : proposal.changes()) {
                String fileName = java.nio.file.Path.of(change.path()).getFileName().toString();
                String baseName = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
                if (test.testSuite() != null && test.testSuite().contains(baseName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
