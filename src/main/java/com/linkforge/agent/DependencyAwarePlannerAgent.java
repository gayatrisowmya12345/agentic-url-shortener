package com.linkforge.agent;

import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.scenario.RepositoryEvidence;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic specialist component for generating a dependency-aware task graph
 * from accepted requirements.
 * Scenario-aware: produces evidence-based tasks for brownfield changes while ensuring
 * greenfield requests do not claim repository findings.
 */
@Component
public class DependencyAwarePlannerAgent {

    public static final String AGENT_NAME = "deterministic-dependency-planner";
    public static final String AGENT_TYPE = "DETERMINISTIC_SPECIALIST";
    public static final String AGENT_DESCRIPTION =
            "Deterministic rule-based agent for dependency-aware task graph decomposition (No LLM connected).";

    public TaskPlanningResult plan(List<String> acceptanceCriteria, String requirement) {
        return plan(acceptanceCriteria, requirement, null);
    }

    public TaskPlanningResult plan(List<String> acceptanceCriteria, String requirement, RepositoryEvidence evidence) {
        if (evidence != null && evidence.hasEvidence()) {
            return planBrownfield(acceptanceCriteria, requirement, evidence);
        }
        return planGreenfield(acceptanceCriteria, requirement);
    }

    private TaskPlanningResult planGreenfield(List<String> acceptanceCriteria, String requirement) {
        List<PlannedTask> tasks = List.of(
                new PlannedTask(
                        "TASK-1",
                        "Core Domain Models & Thread-Safe Store",
                        "Design Link entity (id, originalUrl, token, clickCount, createdAt) and ConcurrentHashMap storage registry.",
                        List.of(),
                        "PENDING",
                        "DATA_PERSISTENCE"
                ),
                new PlannedTask(
                        "TASK-2",
                        "URL Validation & Scheme Sanitization Engine",
                        "Implement strict HTTP/HTTPS URI protocol checks, RFC 3986 format validation, and rejection of malformed URLs.",
                        List.of("TASK-1"),
                        "PENDING",
                        "SECURITY_VALIDATION"
                ),
                new PlannedTask(
                        "TASK-3",
                        "Collision-Free Token Generator",
                        "Implement Base62 token generator with deterministic pseudo-random hashing and collision detection.",
                        List.of("TASK-1"),
                        "PENDING",
                        "API_BEHAVIOR"
                ),
                new PlannedTask(
                        "TASK-4",
                        "Redirection Controller & Atomic Analytics",
                        "Implement GET /{token} handler returning HTTP 302 redirect along with atomic access count increments.",
                        List.of("TASK-2", "TASK-3"),
                        "PENDING",
                        "API_BEHAVIOR"
                ),
                new PlannedTask(
                        "TASK-5",
                        "Automated Verification Suite",
                        "Create comprehensive MockMvc tests verifying link generation, 302 redirection, 400 validation, and 404 missing routes.",
                        List.of("TASK-4"),
                        "PENDING",
                        "TESTING_QUALITY"
                )
        );

        String rationale = "Decomposed verified acceptance criteria into 5 sequential and parallel engineering tasks with explicit DAG dependency constraints.";

        Map<String, Object> metadata = Map.of(
                "agent", AGENT_NAME,
                "type", AGENT_TYPE,
                "scenario", "GREENFIELD",
                "evidenceInformed", false,
                "totalTasks", tasks.size(),
                "rootTaskCount", 1,
                "graphStructure", "DIRECTED_ACYCLIC_GRAPH"
        );

        return new TaskPlanningResult("URL_SHORTENER_EXECUTION_PLAN", rationale, tasks, metadata);
    }

    private TaskPlanningResult planBrownfield(List<String> acceptanceCriteria, String requirement, RepositoryEvidence evidence) {
        String manifests = evidence.projectFileNames().isEmpty() ? "standard manifests" : String.join(", ", evidence.projectFileNames());
        String frameworks = evidence.detectedFrameworks().isEmpty() ? "detected conventions" : String.join(", ", evidence.detectedFrameworks());
        String languages = evidence.detectedLanguages().isEmpty() ? "source files" : String.join(", ", evidence.detectedLanguages());
        String sampleSource = evidence.sampleSourcePaths().isEmpty() ? "core modules" : evidence.sampleSourcePaths().get(0);

        List<PlannedTask> tasks = List.of(
                new PlannedTask(
                        "TASK-1",
                        "Codebase Baseline Inspection & Dependency Analysis",
                        "Review existing codebase structure at " + evidence.repositoryPath() + " with manifests: " + manifests + ".",
                        List.of(),
                        "PENDING",
                        "DATA_PERSISTENCE"
                ),
                new PlannedTask(
                        "TASK-2",
                        "Codebase Extension & Architectural Alignment",
                        "Implement modifications following existing " + frameworks + " architecture and " + languages + " conventions.",
                        List.of("TASK-1"),
                        "PENDING",
                        "API_BEHAVIOR"
                ),
                new PlannedTask(
                        "TASK-3",
                        "Regression Verification & Source Compatibility",
                        "Verify backwards compatibility against existing codebase components (" + sampleSource + ").",
                        List.of("TASK-2"),
                        "PENDING",
                        "TESTING_QUALITY"
                )
        );

        String rationale = "Formulated brownfield engineering plan grounded in inspected repository evidence (" +
                evidence.totalFiles() + " files, " + frameworks + ").";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agent", AGENT_NAME);
        metadata.put("type", AGENT_TYPE);
        metadata.put("scenario", "BROWNFIELD");
        metadata.put("evidenceInformed", true);
        metadata.put("repositoryPath", evidence.repositoryPath());
        metadata.put("detectedFrameworks", evidence.detectedFrameworks());
        metadata.put("detectedLanguages", evidence.detectedLanguages());
        metadata.put("totalTasks", tasks.size());
        metadata.put("graphStructure", "DIRECTED_ACYCLIC_GRAPH");

        return new TaskPlanningResult("BROWNFIELD_INTEGRATION_PLAN", rationale, tasks, metadata);
    }
}
