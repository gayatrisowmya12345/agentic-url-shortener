package com.linkforge.service.coordination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.agent.specialist.ApiBehaviorSpecialistAgent;
import com.linkforge.agent.specialist.DataPersistenceSpecialistAgent;
import com.linkforge.agent.specialist.SecurityValidationSpecialistAgent;
import com.linkforge.agent.specialist.SpecialistAgent;
import com.linkforge.agent.specialist.SpecialistRegistry;
import com.linkforge.agent.specialist.TestingQualitySpecialistAgent;
import com.linkforge.domain.workflow.PlannedTask;
import com.linkforge.domain.workflow.scenario.Scenario;
import com.linkforge.domain.workflow.specialist.SpecialistInvocation;
import com.linkforge.domain.workflow.specialist.SpecialistRole;
import com.linkforge.domain.workflow.specialist.SpecialistTaskInput;
import com.linkforge.domain.workflow.specialist.SpecialistTaskResult;
import com.linkforge.domain.workflow.specialist.exception.MissingDependencyException;
import com.linkforge.domain.workflow.specialist.exception.SpecialistExecutionException;
import com.linkforge.domain.workflow.specialist.exception.TaskGraphCycleException;
import com.linkforge.domain.workflow.specialist.exception.TaskGraphException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskGraphCoordinatorTest {

    private TaskGraphValidator validator;
    private SpecialistRegistry registry;
    private SpecialistCoordinationProperties properties;
    private TaskGraphCoordinator coordinator;

    @BeforeEach
    void setUp() {
        validator = new TaskGraphValidator();
        ObjectMapper mapper = new ObjectMapper();
        List<SpecialistAgent> agents = List.of(
                new ApiBehaviorSpecialistAgent(null, mapper),
                new DataPersistenceSpecialistAgent(null, mapper),
                new SecurityValidationSpecialistAgent(null, mapper),
                new TestingQualitySpecialistAgent(null, mapper)
        );
        registry = new SpecialistRegistry(agents);
        properties = new SpecialistCoordinationProperties();
        properties.setMaxConcurrency(2);
        properties.setTaskTimeoutSeconds(5);
        properties.setMaxInvocations(10);
        properties.setMaxOutputChars(500);

        coordinator = new TaskGraphCoordinator(validator, registry, properties);
    }

    @Test
    @DisplayName("Rejects task graph containing direct cycle (A -> B -> A)")
    void rejectsDirectCycle() {
        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Task 1", "Desc 1", List.of("TASK-2"), "PENDING"),
                new PlannedTask("TASK-2", "Task 2", "Desc 2", List.of("TASK-1"), "PENDING")
        );

        assertThatThrownBy(() -> coordinator.coordinate(tasks, "Req", List.of(), Scenario.GREENFIELD, null))
                .isInstanceOf(TaskGraphCycleException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    @DisplayName("Rejects task graph containing indirect multi-task cycle (A -> B -> C -> A)")
    void rejectsIndirectCycle() {
        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-A", "Task A", "Desc A", List.of("TASK-C"), "PENDING"),
                new PlannedTask("TASK-B", "Task B", "Desc B", List.of("TASK-A"), "PENDING"),
                new PlannedTask("TASK-C", "Task C", "Desc C", List.of("TASK-B"), "PENDING")
        );

        assertThatThrownBy(() -> coordinator.coordinate(tasks, "Req", List.of(), Scenario.GREENFIELD, null))
                .isInstanceOf(TaskGraphCycleException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    @DisplayName("Rejects task specifying unknown/missing dependency")
    void rejectsMissingDependency() {
        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Task 1", "Desc 1", List.of("NON_EXISTENT"), "PENDING")
        );

        assertThatThrownBy(() -> coordinator.coordinate(tasks, "Req", List.of(), Scenario.GREENFIELD, null))
                .isInstanceOf(MissingDependencyException.class)
                .hasMessageContaining("unknown prerequisite dependency: 'NON_EXISTENT'");
    }

    @Test
    @DisplayName("Rejects duplicate task IDs")
    void rejectsDuplicateTaskIds() {
        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Task 1A", "Desc", List.of(), "PENDING"),
                new PlannedTask("TASK-1", "Task 1B", "Desc", List.of(), "PENDING")
        );

        assertThatThrownBy(() -> coordinator.coordinate(tasks, "Req", List.of(), Scenario.GREENFIELD, null))
                .isInstanceOf(TaskGraphException.class)
                .hasMessageContaining("Duplicate task ID detected");
    }

    @Test
    @DisplayName("Rejects task count exceeding configured maximum invocations")
    void rejectsExceedingMaxInvocationsLimit() {
        properties.setMaxInvocations(2);

        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Task 1", "Desc", List.of(), "PENDING"),
                new PlannedTask("TASK-2", "Task 2", "Desc", List.of(), "PENDING"),
                new PlannedTask("TASK-3", "Task 3", "Desc", List.of(), "PENDING")
        );

        assertThatThrownBy(() -> coordinator.coordinate(tasks, "Req", List.of(), Scenario.GREENFIELD, null))
                .isInstanceOf(SpecialistExecutionException.class)
                .hasMessageContaining("exceeds maximum allowed invocations");
    }

    @Test
    @DisplayName("Executes tasks strictly adhering to dependency order (TASK-4 runs only after TASK-2 and TASK-3)")
    void executesInStrictDependencyOrder() {
        Map<String, Instant> startTimes = new ConcurrentHashMap<>();
        Map<String, Instant> endTimes = new ConcurrentHashMap<>();

        SpecialistAgent recordingAgent = new SpecialistAgent() {
            @Override public SpecialistRole getRole() { return SpecialistRole.API_BEHAVIOR; }
            @Override public String getAgentName() { return "recording-agent"; }
            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                Instant start = Instant.now();
                startTimes.put(input.taskId(), start);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}
                Instant end = Instant.now();
                endTimes.put(input.taskId(), end);

                return new SpecialistTaskResult(
                        input.taskId(), getRole(), getAgentName(), "SUCCESS",
                        "Summary for " + input.taskId(), List.of("Rec"), List.of("Test"), List.of("AC-1"),
                        Map.of(), false, null, start, end
                );
            }
        };

        SpecialistRegistry customRegistry = new SpecialistRegistry(List.of(recordingAgent));
        TaskGraphCoordinator customCoord = new TaskGraphCoordinator(validator, customRegistry, properties);

        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Root", "Desc", List.of(), "PENDING"),
                new PlannedTask("TASK-2", "Left", "Desc", List.of("TASK-1"), "PENDING"),
                new PlannedTask("TASK-3", "Right", "Desc", List.of("TASK-1"), "PENDING"),
                new PlannedTask("TASK-4", "Merge", "Desc", List.of("TASK-2", "TASK-3"), "PENDING")
        );

        CoordinationResult result = customCoord.coordinate(tasks, "Req", List.of("AC-1"), Scenario.GREENFIELD, null);

        assertThat(result.allSuccessful()).isTrue();
        assertThat(result.completedCount()).isEqualTo(4);

        // TASK-2 and TASK-3 must start after TASK-1 completes
        assertThat(startTimes.get("TASK-2")).isAfterOrEqualTo(endTimes.get("TASK-1"));
        assertThat(startTimes.get("TASK-3")).isAfterOrEqualTo(endTimes.get("TASK-1"));

        // TASK-4 must start after BOTH TASK-2 and TASK-3 complete
        assertThat(startTimes.get("TASK-4")).isAfterOrEqualTo(endTimes.get("TASK-2"));
        assertThat(startTimes.get("TASK-4")).isAfterOrEqualTo(endTimes.get("TASK-3"));
    }

    @Test
    @DisplayName("Executes independent tasks concurrently up to concurrency limit")
    void executesIndependentTasksConcurrently() throws InterruptedException {
        properties.setMaxConcurrency(2);
        CountDownLatch startLatch = new CountDownLatch(2);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentRunning = new AtomicInteger(0);

        SpecialistAgent concurrentAgent = new SpecialistAgent() {
            @Override public SpecialistRole getRole() { return SpecialistRole.API_BEHAVIOR; }
            @Override public String getAgentName() { return "concurrent-agent"; }
            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                int running = currentRunning.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, running));
                startLatch.countDown();
                try {
                    startLatch.await(2, TimeUnit.SECONDS);
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {} finally {
                    currentRunning.decrementAndGet();
                }
                return new SpecialistTaskResult(
                        input.taskId(), getRole(), getAgentName(), "SUCCESS",
                        "Summary", List.of("Rec"), List.of("Test"), List.of("AC-1"),
                        Map.of(), false, null, Instant.now(), Instant.now()
                );
            }
        };

        SpecialistRegistry customRegistry = new SpecialistRegistry(List.of(concurrentAgent));
        TaskGraphCoordinator customCoord = new TaskGraphCoordinator(validator, customRegistry, properties);

        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Independent 1", "Desc", List.of(), "PENDING"),
                new PlannedTask("TASK-2", "Independent 2", "Desc", List.of(), "PENDING")
        );

        CoordinationResult result = customCoord.coordinate(tasks, "Req", List.of("AC-1"), Scenario.GREENFIELD, null);

        assertThat(result.allSuccessful()).isTrue();
        // Concurrency was at least 2
        assertThat(maxConcurrent.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Preserves deterministic task ordering in final response matching original plan sequence")
    void preservesDeterministicOrderingInFinalResponse() {
        SpecialistAgent delayedAgent = new SpecialistAgent() {
            @Override public SpecialistRole getRole() { return SpecialistRole.API_BEHAVIOR; }
            @Override public String getAgentName() { return "delayed-agent"; }
            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                if ("TASK-1".equals(input.taskId())) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
                return new SpecialistTaskResult(
                        input.taskId(), getRole(), getAgentName(), "SUCCESS",
                        "Summary " + input.taskId(), List.of("Rec"), List.of("Test"), List.of("AC-1"),
                        Map.of(), false, null, Instant.now(), Instant.now()
                );
            }
        };

        SpecialistRegistry customRegistry = new SpecialistRegistry(List.of(delayedAgent));
        TaskGraphCoordinator customCoord = new TaskGraphCoordinator(validator, customRegistry, properties);

        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "First", "Desc", List.of(), "PENDING"),
                new PlannedTask("TASK-2", "Second", "Desc", List.of(), "PENDING"),
                new PlannedTask("TASK-3", "Third", "Desc", List.of(), "PENDING")
        );

        CoordinationResult result = customCoord.coordinate(tasks, "Req", List.of("AC-1"), Scenario.GREENFIELD, null);

        assertThat(result.invocations().stream().map(SpecialistInvocation::taskId).toList())
                .containsExactly("TASK-1", "TASK-2", "TASK-3");
        assertThat(result.updatedTasks().stream().map(PlannedTask::taskId).toList())
                .containsExactly("TASK-1", "TASK-2", "TASK-3");
    }

    @Test
    @DisplayName("Handles task failure gracefully: preserves completed results and marks dependent tasks as SKIPPED")
    void handlesTaskFailureWithoutLosingCompletedResults() {
        SpecialistAgent failingAgent = new SpecialistAgent() {
            @Override public SpecialistRole getRole() { return SpecialistRole.API_BEHAVIOR; }
            @Override public String getAgentName() { return "failing-agent"; }
            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                if ("TASK-2".equals(input.taskId())) {
                    return new SpecialistTaskResult(
                            input.taskId(), getRole(), getAgentName(), "FAILED",
                            "Simulated failure on task 2", List.of(), List.of(), List.of(),
                            Map.of(), true, "SimulatedError", Instant.now(), Instant.now()
                    );
                }
                return new SpecialistTaskResult(
                        input.taskId(), getRole(), getAgentName(), "SUCCESS",
                        "Success for " + input.taskId(), List.of("Rec"), List.of("Test"), List.of("AC-1"),
                        Map.of(), false, null, Instant.now(), Instant.now()
                );
            }
        };

        SpecialistRegistry customRegistry = new SpecialistRegistry(List.of(failingAgent));
        TaskGraphCoordinator customCoord = new TaskGraphCoordinator(validator, customRegistry, properties);

        // TASK-1 (root, succeeds)
        // TASK-2 (dep: 1, fails)
        // TASK-3 (dep: 1, succeeds)
        // TASK-4 (dep: 2, 3 -> blocked because 2 failed -> SKIPPED)
        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Task 1", "Desc", List.of(), "PENDING"),
                new PlannedTask("TASK-2", "Task 2", "Desc", List.of("TASK-1"), "PENDING"),
                new PlannedTask("TASK-3", "Task 3", "Desc", List.of("TASK-1"), "PENDING"),
                new PlannedTask("TASK-4", "Task 4", "Desc", List.of("TASK-2", "TASK-3"), "PENDING")
        );

        CoordinationResult result = customCoord.coordinate(tasks, "Req", List.of("AC-1"), Scenario.GREENFIELD, null);

        assertThat(result.allSuccessful()).isFalse();
        assertThat(result.completedCount()).isEqualTo(2); // TASK-1 and TASK-3 succeeded
        assertThat(result.failedCount()).isEqualTo(1);    // TASK-2 failed
        assertThat(result.skippedCount()).isEqualTo(1);   // TASK-4 skipped

        // Verify task statuses
        Map<String, String> statusMap = result.updatedTasks().stream()
                .collect(ConcurrentHashMap::new, (m, t) -> m.put(t.taskId(), t.status()), Map::putAll);
        assertThat(statusMap.get("TASK-1")).isEqualTo("COMPLETED");
        assertThat(statusMap.get("TASK-2")).isEqualTo("FAILED");
        assertThat(statusMap.get("TASK-3")).isEqualTo("COMPLETED");
        assertThat(statusMap.get("TASK-4")).isEqualTo("SKIPPED");

        // Verify invocations: TASK-1, TASK-2, TASK-3 are recorded; TASK-4 was skipped
        assertThat(result.invocations()).hasSize(3);
    }

    @Test
    @DisplayName("Enforces complete output bounding, per-field limits, and serialized response budget")
    void enforcesCompleteOutputBoundingAndSerializedResponseBudget() throws Exception {
        int budget = 450;
        properties.setMaxOutputChars(budget);

        SpecialistAgent verboseAgent = new SpecialistAgent() {
            @Override public SpecialistRole getRole() { return SpecialistRole.API_BEHAVIOR; }
            @Override public String getAgentName() { return "verbose-agent"; }
            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                return new SpecialistTaskResult(
                        input.taskId(),
                        getRole(),
                        getAgentName(),
                        "SUCCESS",
                        "This is a verbose specialist summary exceeding default limits and filling the budget space.",
                        List.of("Recommendation A with extensive guidance", "Recommendation B with extensive guidance", "Recommendation C with extensive guidance"),
                        List.of("Test idea A with full coverage details", "Test idea B with full coverage details"),
                        List.of("AC-1", "AC-2"),
                        Map.of(),
                        false,
                        null,
                        Instant.now(),
                        Instant.now()
                );
            }
        };

        SpecialistRegistry customRegistry = new SpecialistRegistry(List.of(verboseAgent));
        TaskGraphCoordinator customCoord = new TaskGraphCoordinator(validator, customRegistry, properties);

        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Verbose Task", "Description", List.of(), "PENDING")
        );

        CoordinationResult result = customCoord.coordinate(tasks, "Req", List.of("AC-1", "AC-2"), Scenario.GREENFIELD, null);

        assertThat(result.allSuccessful()).isTrue();
        assertThat(result.invocations()).hasSize(1);

        SpecialistInvocation invocation = result.invocations().get(0);
        assertThat(invocation.recommendations().size()).isLessThanOrEqualTo(10);
        assertThat(invocation.testIdeas().size()).isLessThanOrEqualTo(10);
        assertThat(invocation.addressedCriteria().size()).isLessThanOrEqualTo(10);

        // Verify per-item length bounds
        for (String r : invocation.recommendations()) {
            assertThat(r.length()).isLessThanOrEqualTo(500);
        }
        for (String t : invocation.testIdeas()) {
            assertThat(t.length()).isLessThanOrEqualTo(500);
        }
        for (String c : invocation.addressedCriteria()) {
            assertThat(c.length()).isLessThanOrEqualTo(50);
        }

        // Verify serialized JSON strictly does not exceed maxBudget
        ObjectMapper mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String serializedJson = mapper.writeValueAsString(invocation);
        assertThat(serializedJson.length()).isLessThanOrEqualTo(budget);
    }

    @Test
    @DisplayName("Serialized response stays within small budget when fallbackReason, provider, model, and input fields are unusually long")
    void serializedResponseStaysWithinBudgetWithUnusuallyLongFields() throws Exception {
        int smallBudget = 550;
        properties.setMaxOutputChars(smallBudget);

        SpecialistAgent longFieldsAgent = new SpecialistAgent() {
            @Override public SpecialistRole getRole() { return SpecialistRole.API_BEHAVIOR; }
            @Override public String getAgentName() { return "long-fields-agent"; }
            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                Map<String, Object> metadata = Map.of(
                        "provider", "custom-enterprise-ollama-cluster-us-east-1-zone-b-primary-instance",
                        "model", "llama-3.2-8b-instruct-quantized-fp16-extended-context-window-v2-production",
                        "type", "DETERMINISTIC_SPECIALIST_FALLBACK"
                );
                return new SpecialistTaskResult(
                        input.taskId(),
                        getRole(),
                        getAgentName(),
                        "SUCCESS",
                        "Extensive output summary that explains all aspects of the architecture and HTTP behaviors in great detail.",
                        List.of("Recommendation 1 with extensive guidance", "Recommendation 2 with extensive guidance"),
                        List.of("Test idea 1 with detailed assertions", "Test idea 2 with detailed assertions"),
                        List.of("AC-1", "AC-2"),
                        metadata,
                        true,
                        "Extremely verbose fallback reason documenting the full stack trace and network connection diagnostics in depth.",
                        Instant.now(),
                        Instant.now()
                );
            }
        };

        SpecialistRegistry customRegistry = new SpecialistRegistry(List.of(longFieldsAgent));
        TaskGraphCoordinator customCoord = new TaskGraphCoordinator(validator, customRegistry, properties);

        String longTaskTitle = "Unusually Long Task Title Designed To Test Input Summary Bounding When Field Lengths Are Large " + "X".repeat(150);
        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", longTaskTitle, "Task Description", List.of(), "PENDING")
        );

        CoordinationResult result = customCoord.coordinate(tasks, "Req", List.of("AC-1", "AC-2"), Scenario.GREENFIELD, null);

        assertThat(result.invocations()).hasSize(1);
        SpecialistInvocation invocation = result.invocations().get(0);

        // Verify that variable fields were bounded
        assertThat(invocation.fallbackReason()).isNotNull();
        assertThat(invocation.provider()).isNotBlank();
        assertThat(invocation.model()).isNotBlank();
        assertThat(invocation.inputSummary()).isNotBlank();

        // Verify serialized JSON strictly stays within small configured budget
        ObjectMapper mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String serializedJson = mapper.writeValueAsString(invocation);
        assertThat(serializedJson.length()).isLessThanOrEqualTo(smallBudget);
    }

    @Test
    @DisplayName("Fails safely with clear error when configured output budget is too small to satisfy")
    void failsSafelyWhenBudgetTooSmallToSatisfy() {
        // A budget of 100 characters cannot even fit minimal JSON with invocationId (36 chars) + timestamps + keys
        properties.setMaxOutputChars(100);

        SpecialistAgent simpleAgent = new SpecialistAgent() {
            @Override public SpecialistRole getRole() { return SpecialistRole.API_BEHAVIOR; }
            @Override public String getAgentName() { return "simple-agent"; }
            @Override
            public SpecialistTaskResult execute(SpecialistTaskInput input) {
                return new SpecialistTaskResult(
                        input.taskId(),
                        getRole(),
                        getAgentName(),
                        "SUCCESS",
                        "Summary",
                        List.of("Rec"),
                        List.of("Test"),
                        List.of("AC-1"),
                        Map.of(),
                        false,
                        null,
                        Instant.now(),
                        Instant.now()
                );
            }
        };

        SpecialistRegistry customRegistry = new SpecialistRegistry(List.of(simpleAgent));
        TaskGraphCoordinator customCoord = new TaskGraphCoordinator(validator, customRegistry, properties);

        List<PlannedTask> tasks = List.of(
                new PlannedTask("TASK-1", "Task Title", "Task Desc", List.of(), "PENDING")
        );

        assertThatThrownBy(() -> customCoord.coordinate(tasks, "Req", List.of("AC-1"), Scenario.GREENFIELD, null))
                .isInstanceOf(SpecialistExecutionException.class)
                .hasMessageContaining("too small to fit specialist invocation");
    }
}
