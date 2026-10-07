package com.linkforge.agent.specialist;

import com.linkforge.domain.workflow.implementation.BuildDiagnosisResult;
import com.linkforge.domain.workflow.implementation.BuildValidationResult;
import com.linkforge.domain.workflow.implementation.ImplementationProposal;
import com.linkforge.domain.workflow.implementation.TestReportItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Specialist agent that diagnoses non-transient build, compilation, and test validation
 * failures, identifying root causes, affected files, and criteria.
 */
@Component
public class BuildDiagnosisSpecialistAgent {

    private static final Logger log = LoggerFactory.getLogger(BuildDiagnosisSpecialistAgent.class);

    public BuildDiagnosisResult diagnose(
            BuildValidationResult buildResult,
            ImplementationProposal proposal,
            List<String> acceptanceCriteria
    ) {
        String diagnosisId = UUID.randomUUID().toString();
        String output = buildResult != null ? buildResult.output() : "";
        List<TestReportItem> failedTests = buildResult != null
                ? buildResult.testReports().stream().filter(TestReportItem::isFailed).toList()
                : List.of();

        List<String> affectedFiles = new ArrayList<>();
        List<String> affectedCriteria = new ArrayList<>();
        String likelyCause;
        boolean repairable = true;
        String recommendedAction;

        if (!failedTests.isEmpty()) {
            TestReportItem firstFailed = failedTests.get(0);
            String testName = firstFailed.testName();
            String msg = firstFailed.failureMessage() != null ? firstFailed.failureMessage() : "";

            if (testName.toLowerCase().contains("alias") || msg.toLowerCase().contains("alias")) {
                affectedFiles.add("src/main/java/com/linkforge/service/link/AliasValidator.java");
                affectedCriteria.add("AC-ALIAS");
                likelyCause = "Custom alias validation boundary condition failure in " + testName +
                        (msg.isBlank() ? "" : ": " + msg);
                recommendedAction = "Update AliasValidator.java length bounds and regex pattern to satisfy acceptance criteria.";
            } else if (testName.toLowerCase().contains("domain") || msg.toLowerCase().contains("domain")) {
                affectedFiles.add("src/main/java/com/linkforge/service/link/LinkShortenerService.java");
                affectedCriteria.add("AC-DOMAIN");
                likelyCause = "Domain restriction validation failure in " + testName;
                recommendedAction = "Update LinkShortenerService.java destination URL validation policy.";
            } else {
                likelyCause = "Test assertion failure in " + firstFailed.testSuite() + "." + testName +
                        (msg.isBlank() ? "" : ": " + msg);
                if (proposal != null && !proposal.changes().isEmpty()) {
                    affectedFiles.add(proposal.changes().get(0).path());
                }
                recommendedAction = "Inspect assertion details and align implementation with test expectations.";
            }
        } else if (output.toUpperCase().contains("[ERROR]") || output.toUpperCase().contains("COMPILATION ERROR") || output.toLowerCase().contains("syntax error")) {
            likelyCause = "Compilation failure detected in proposed source changes: syntax or symbol error.";
            repairable = true;
            if (proposal != null) {
                proposal.changes().forEach(c -> affectedFiles.add(c.path()));
            }
            recommendedAction = "Apply corrective syntax and signature repairs to align with Java language specifications.";
        } else if ("TIMED_OUT".equalsIgnoreCase(buildResult != null ? buildResult.status() : "")) {
            likelyCause = "Build execution timed out; potential infinite loop or blocking process.";
            repairable = false;
            recommendedAction = "Review process execution constraints; human intervention required.";
        } else {
            likelyCause = "Build validation exited with code " + (buildResult != null ? buildResult.exitCode() : -1) + ".";
            if (proposal != null && !proposal.changes().isEmpty()) {
                affectedFiles.add(proposal.changes().get(0).path());
            }
            recommendedAction = "Inspect Maven execution log for root cause.";
        }

        if (affectedFiles.isEmpty() && proposal != null) {
            proposal.changes().forEach(c -> affectedFiles.add(c.path()));
        }

        log.info("Build diagnosis {} completed: likelyCause='{}', affectedFiles={}, repairable={}",
                diagnosisId, likelyCause, affectedFiles, repairable);

        return new BuildDiagnosisResult(
                diagnosisId,
                likelyCause,
                affectedFiles,
                affectedCriteria,
                repairable,
                recommendedAction,
                Instant.now()
        );
    }
}
