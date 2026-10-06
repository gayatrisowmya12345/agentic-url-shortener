package com.linkforge.service;

import com.linkforge.agent.DependencyAwarePlannerAgent;
import com.linkforge.agent.RequirementInterpreterAgent;
import com.linkforge.agent.ScenarioClassifierAgent;
import com.linkforge.domain.workflow.WorkflowEvent;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.domain.workflow.WorkflowStage;
import com.linkforge.domain.workflow.WorkflowStatus;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import com.linkforge.service.inspection.CodebaseInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowOrchestratorScenarioTest {

    @TempDir
    Path approvedRoot;

    private WorkflowOrchestrator orchestrator;
    private WorkflowRepository repository;
    private CodebaseInspector inspector;

    @BeforeEach
    void setUp() {
        CodebaseInspectionProperties props = new CodebaseInspectionProperties();
        props.setApprovedRoot(approvedRoot.toString());
        inspector = new CodebaseInspector(props);

        ScenarioClassifierAgent classifier = new ScenarioClassifierAgent();
        RequirementInterpreterAgent interpreter = new RequirementInterpreterAgent();
        DependencyAwarePlannerAgent planner = new DependencyAwarePlannerAgent();
        repository = new WorkflowRepository();

        orchestrator = new WorkflowOrchestrator(classifier, inspector, interpreter, planner, repository);
    }

    @Test
    @DisplayName("GREENFIELD workflow is plannable without repository path and claims no repository findings")
    void greenfieldWorkflowPlannableWithoutRepo() {
        String requirement = "Build a URL shortening REST API with token hashing and HTTP 302 redirects";

        WorkflowRun run = orchestrator.startWorkflow(requirement);

        assertThat(run).isNotNull();
        assertThat(run.getScenario()).isEqualTo(Scenario.GREENFIELD);
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.FINISHED);
        assertThat(run.getRepositoryPath()).isNull();
        assertThat(run.getRepositoryEvidence().hasEvidence()).isFalse();

        // 5 standard greenfield tasks generated
        assertThat(run.getTasks()).hasSize(5);

        // Planner decision records evidenceInformed = false
        var plannerDecision = run.getAgentDecisions().stream()
                .filter(d -> d.agentName().equals(DependencyAwarePlannerAgent.AGENT_NAME))
                .findFirst()
                .orElseThrow();
        assertThat(plannerDecision.metadata().get("evidenceInformed")).isEqualTo(false);
        assertThat(plannerDecision.metadata().get("scenario")).isEqualTo("GREENFIELD");
    }

    @Test
    @DisplayName("BROWNFIELD workflow inspects repository fixture and grounds plan in recorded evidence")
    void brownfieldWorkflowWithValidRepoFixture() throws IOException {
        Path repoFixture = approvedRoot.resolve("existing-link-service");
        Files.createDirectories(repoFixture.resolve("src/main/java/com/linkforge/legacy"));
        Files.writeString(repoFixture.resolve("pom.xml"), """
                <project>
                  <groupId>com.linkforge</groupId>
                  <artifactId>legacy-service</artifactId>
                  <version>1.0</version>
                </project>
                """);
        Files.writeString(repoFixture.resolve("src/main/java/com/linkforge/legacy/LegacyStore.java"),
                "package com.linkforge.legacy; public class LegacyStore {}");

        String requirement = "Refactor the existing repository to upgrade database persistence";

        WorkflowRun run = orchestrator.startWorkflow(requirement, "existing-link-service");

        assertThat(run.getScenario()).isEqualTo(Scenario.BROWNFIELD);
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.FINISHED);
        assertThat(run.getRepositoryEvidence()).isNotNull();
        assertThat(run.getRepositoryEvidence().hasEvidence()).isTrue();
        assertThat(run.getRepositoryEvidence().detectedLanguages()).contains("Java");
        assertThat(run.getRepositoryEvidence().detectedFrameworks()).contains("Maven");

        // Event history recorded inspection
        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains("INSPECTION_STARTED", "INSPECTION_COMPLETED");

        // Brownfield tasks generated with evidence reference
        assertThat(run.getTasks()).hasSize(3);
        assertThat(run.getTasks().get(0).title()).contains("Codebase Baseline Inspection");

        var plannerDecision = run.getAgentDecisions().stream()
                .filter(d -> d.agentName().equals(DependencyAwarePlannerAgent.AGENT_NAME))
                .findFirst()
                .orElseThrow();
        assertThat(plannerDecision.metadata().get("evidenceInformed")).isEqualTo(true);
        assertThat(plannerDecision.metadata().get("scenario")).isEqualTo("BROWNFIELD");
    }

    @Test
    @DisplayName("BROWNFIELD workflow with absolute repository path fails and rejects execution")
    void brownfieldWorkflowAbsolutePathFails() throws IOException {
        Path repoFixture = approvedRoot.resolve("abs-repo-test");
        Files.createDirectories(repoFixture);
        Files.writeString(repoFixture.resolve("pom.xml"), "<project></project>");

        WorkflowRun run = orchestrator.startWorkflow("Refactor existing codebase", repoFixture.toAbsolutePath().toString());

        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.CODEBASE_INSPECTION);
        assertThat(run.getTasks()).isEmpty();

        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains("INSPECTION_FAILED");
    }

    @Test
    @DisplayName("BROWNFIELD workflow without repository path pauses in WAITING_FOR_CLARIFICATION without plan")
    void brownfieldWorkflowMissingRepoPausesForClarification() {
        String requirement = "Refactor the existing codebase to replace in-memory storage";

        WorkflowRun run = orchestrator.startWorkflow(requirement, null);

        assertThat(run.getScenario()).isEqualTo(Scenario.BROWNFIELD);
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.CODEBASE_INSPECTION);
        assertThat(run.getUnansweredQuestions()).anyMatch(q -> q.toLowerCase().contains("repository path"));

        // No tasks generated
        assertThat(run.getTasks()).isEmpty();

        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains("MISSING_REPOSITORY_PATH", "WORKFLOW_PAUSED");
        assertThat(eventTypes).doesNotContain("PLANNING_STARTED", "PLAN_GENERATED");
    }

    @Test
    @DisplayName("BROWNFIELD workflow with path traversal escape transitions to FAILED without plan")
    void brownfieldWorkflowTraversalFails() {
        String requirement = "Refactor existing repository";
        String traversalPath = "../outside-root-escape";

        WorkflowRun run = orchestrator.startWorkflow(requirement, traversalPath);

        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.CODEBASE_INSPECTION);
        assertThat(run.getTasks()).isEmpty();

        List<String> eventTypes = run.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains("INSPECTION_FAILED");
        assertThat(eventTypes).doesNotContain("PLAN_GENERATED");
    }

    @Test
    @DisplayName("AMBIGUOUS workflow pauses in WAITING_FOR_CLARIFICATION and does not plan tasks")
    void ambiguousWorkflowPausesWithoutPlan() {
        String requirement = "make links faster and safer";

        WorkflowRun run = orchestrator.startWorkflow(requirement);

        assertThat(run.getScenario()).isEqualTo(Scenario.AMBIGUOUS);
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);
        assertThat(run.getCurrentStage()).isEqualTo(WorkflowStage.SCENARIO_CLASSIFICATION);
        assertThat(run.getUnansweredQuestions()).isNotEmpty();
        assertThat(run.getTasks()).isEmpty();
    }

    @Test
    @DisplayName("Clarification submission unblocks ambiguous workflow to completion")
    void submitClarificationUnblocksWorkflow() {
        WorkflowRun run = orchestrator.startWorkflow("make links faster and safer");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        String clarification = "Create a greenfield REST service for shortening URLs with Base62 tokens";
        Optional<WorkflowRun> resumed = orchestrator.submitClarification(run.getId(), clarification, null);

        assertThat(resumed).isPresent();
        WorkflowRun completed = resumed.get();
        assertThat(completed.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.getCurrentStage()).isEqualTo(WorkflowStage.FINISHED);
        assertThat(completed.getScenario()).isEqualTo(Scenario.GREENFIELD);
        assertThat(completed.getTasks()).hasSize(5);

        List<String> eventTypes = completed.getEvents().stream().map(WorkflowEvent::eventType).toList();
        assertThat(eventTypes).contains("CLARIFICATION_SUBMITTED", "WORKFLOW_COMPLETED");
    }

    @Test
    @DisplayName("Clarification submission for brownfield missing repo unblocks workflow with supplied repo path")
    void submitClarificationWithRepoPathForBrownfield() throws IOException {
        Path repoFixture = approvedRoot.resolve("brownfield-resumed");
        Files.createDirectories(repoFixture.resolve("src"));
        Files.writeString(repoFixture.resolve("pom.xml"), "<project><artifactId>resumed</artifactId></project>");

        WorkflowRun run = orchestrator.startWorkflow("Refactor existing codebase", null);
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.WAITING_FOR_CLARIFICATION);

        Optional<WorkflowRun> resumed = orchestrator.submitClarification(
                run.getId(),
                "Target repository is now provided",
                "brownfield-resumed"
        );

        assertThat(resumed).isPresent();
        WorkflowRun completed = resumed.get();
        assertThat(completed.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.getScenario()).isEqualTo(Scenario.BROWNFIELD);
        assertThat(completed.getRepositoryEvidence().hasEvidence()).isTrue();
        assertThat(completed.getTasks()).hasSize(3);
    }

    @Test
    @DisplayName("Submitting clarification to a workflow not waiting for clarification throws IllegalStateException")
    void submitClarificationToCompletedWorkflowThrows() {
        WorkflowRun run = orchestrator.startWorkflow("Build a new URL shortener");
        assertThat(run.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

        assertThatThrownBy(() -> orchestrator.submitClarification(run.getId(), "Extra details", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not waiting for clarification");
    }

    @Test
    @DisplayName("Submitting clarification for non-existent workflow returns empty Optional")
    void submitClarificationNonExistentId() {
        Optional<WorkflowRun> result = orchestrator.submitClarification("non-existent-id", "Some clarification", null);
        assertThat(result).isEmpty();
    }
}
