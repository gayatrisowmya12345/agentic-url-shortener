package com.linkforge.agent;

import com.linkforge.domain.workflow.PlannedTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyAwarePlannerAgentTest {

    private DependencyAwarePlannerAgent planner;

    @BeforeEach
    void setUp() {
        planner = new DependencyAwarePlannerAgent();
    }

    @Test
    @DisplayName("Planner creates dependency graph without circular references")
    void planTasksWithAcyclicDependencies() {
        List<String> criteria = List.of(
                "AC-1: Shorten valid URL",
                "AC-2: Redirect to original URL"
        );
        String requirement = "Build a URL shortener";

        TaskPlanningResult result = planner.plan(criteria, requirement);

        assertThat(result.tasks()).isNotEmpty();
        assertThat(result.metadata()).containsEntry("agent", DependencyAwarePlannerAgent.AGENT_NAME);
        assertThat(result.metadata()).containsEntry("type", DependencyAwarePlannerAgent.AGENT_TYPE);

        List<PlannedTask> tasks = result.tasks();
        Set<String> declaredTaskIds = new HashSet<>();
        for (PlannedTask t : tasks) {
            declaredTaskIds.add(t.taskId());
        }

        // Verify that all dependencies reference previously defined task IDs in the DAG
        Set<String> processedTaskIds = new HashSet<>();
        for (PlannedTask t : tasks) {
            for (String dep : t.dependencies()) {
                assertThat(declaredTaskIds)
                        .as("Dependency %s must exist in the task plan", dep)
                        .contains(dep);
                assertThat(processedTaskIds)
                        .as("Dependency %s must be defined prior to dependent task %s", dep, t.taskId())
                        .contains(dep);
            }
            processedTaskIds.add(t.taskId());
        }

        // Verify specific task relationships
        PlannedTask rootTask = tasks.stream().filter(t -> t.taskId().equals("TASK-1")).findFirst().orElseThrow();
        assertThat(rootTask.dependencies()).isEmpty();

        PlannedTask validationTask = tasks.stream().filter(t -> t.taskId().equals("TASK-2")).findFirst().orElseThrow();
        assertThat(validationTask.dependencies()).containsExactly("TASK-1");

        PlannedTask tokenTask = tasks.stream().filter(t -> t.taskId().equals("TASK-3")).findFirst().orElseThrow();
        assertThat(tokenTask.dependencies()).containsExactly("TASK-1");

        PlannedTask redirectTask = tasks.stream().filter(t -> t.taskId().equals("TASK-4")).findFirst().orElseThrow();
        assertThat(redirectTask.dependencies()).containsExactlyInAnyOrder("TASK-2", "TASK-3");

        PlannedTask verifyTask = tasks.stream().filter(t -> t.taskId().equals("TASK-5")).findFirst().orElseThrow();
        assertThat(verifyTask.dependencies()).containsExactly("TASK-4");
    }
}
