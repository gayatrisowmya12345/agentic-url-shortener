package com.linkforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.service.WorkflowRepository;
import com.linkforge.service.security.WorkflowSecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowEvidenceAndObservabilityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowSecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        securityProperties.setEnabled(false);
        securityProperties.setOperatorToken("test-operator-token");
        workflowRepository.clear();
    }

    @AfterEach
    void tearDown() {
        securityProperties.setEnabled(false);
        workflowRepository.clear();
    }

    private String createAndApproveWorkflow() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener with Base62 tokens and click analytics"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "lead-architect",
                  "comments": "Approved for execution"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk());

        return id;
    }

    @Test
    @DisplayName("GET /api/v1/workflows/{id}/history returns ordered audit history")
    void getWorkflowHistoryReturnsOrderedEvents() throws Exception {
        String id = createAndApproveWorkflow();

        mockMvc.perform(get("/api/v1/workflows/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(id))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andExpect(jsonPath("$.totalEvents", greaterThan(0)))
                .andExpect(jsonPath("$.events", not(empty())))
                .andExpect(jsonPath("$.events[0].eventId").isNotEmpty())
                .andExpect(jsonPath("$.events[0].eventType").isNotEmpty())
                .andExpect(jsonPath("$.events[0].timestamp").isNotEmpty());

        // Test alias endpoint /events
        mockMvc.perform(get("/api/v1/workflows/" + id + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(id));
    }

    @Test
    @DisplayName("GET /api/v1/workflows/{id}/evidence returns traceability and honest verification bounds")
    void getWorkflowEvidenceReturnsTraceabilityAndVerificationStatus() throws Exception {
        String id = createAndApproveWorkflow();

        mockMvc.perform(get("/api/v1/workflows/" + id + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(id))
                .andExpect(jsonPath("$.scenario").value("GREENFIELD"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.planApproved").value(true))
                .andExpect(jsonPath("$.repositoryPath").doesNotExist())
                .andExpect(jsonPath("$.codebaseEvidenceAvailable").value(false))
                .andExpect(jsonPath("$.eventLinkageStatus").value("EVENT_LEVEL_LINKAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.criteriaEvidence", not(empty())))
                .andExpect(jsonPath("$.criteriaEvidence[0].criterionId").isNotEmpty())
                .andExpect(jsonPath("$.criteriaEvidence[0].status").value("ANALYZED"))
                .andExpect(jsonPath("$.criteriaEvidence[0].relevantEventTypes").isEmpty())
                .andExpect(jsonPath("$.criteriaEvidence[0].eventLinkageStatus").value("EVENT_LEVEL_LINKAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.taskTraceability", not(empty())))
                .andExpect(jsonPath("$.taskTraceability[0].taskId").value("TASK-1"))
                .andExpect(jsonPath("$.taskTraceability[0].specialistExecuted").value(true))
                // Honest verification reporting:
                .andExpect(jsonPath("$.verificationStatus.requirementAnalysis").value("COMPLETED"))
                .andExpect(jsonPath("$.verificationStatus.taskPlanning").value("COMPLETED"))
                .andExpect(jsonPath("$.verificationStatus.specialistAnalysis").value("COMPLETED"))
                .andExpect(jsonPath("$.verificationStatus.sourceCodeGeneration").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.verificationStatus.buildExecution").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.verificationStatus.automatedTestExecution").value("UNVERIFIED"))
                .andExpect(jsonPath("$.verificationStatus.deploymentAndRelease").value("NOT_SUPPORTED"));

        // Test alias endpoint /traceability
        mockMvc.perform(get("/api/v1/workflows/" + id + "/traceability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(id))
                .andExpect(jsonPath("$.repositoryPath").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/workflows/{id}/summary returns focused derived summary")
    void getWorkflowSummaryReturnsDerivedMetrics() throws Exception {
        String id = createAndApproveWorkflow();

        mockMvc.perform(get("/api/v1/workflows/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(id))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.requirementRevision").value(1))
                .andExpect(jsonPath("$.taskCount", greaterThan(0)))
                .andExpect(jsonPath("$.completedTaskCount", greaterThan(0)))
                .andExpect(jsonPath("$.agentDecisionCount", greaterThan(0)))
                .andExpect(jsonPath("$.eventCount", greaterThan(0)))
                .andExpect(jsonPath("$.planApproved").value(true))
                .andExpect(jsonPath("$.completeness.allPlannedTasksExecuted").value(true))
                .andExpect(jsonPath("$.completeness.unverifiedCapabilities", hasItem("SOURCE_CODE_GENERATION")));
    }

    @Test
    @DisplayName("Endpoints return 404 with WORKFLOW_NOT_FOUND for nonexistent workflow ID")
    void missingWorkflowReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/nonexistent-uuid-9999/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WORKFLOW_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/workflows/nonexistent-uuid-9999/evidence"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WORKFLOW_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/workflows/nonexistent-uuid-9999/summary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WORKFLOW_NOT_FOUND"));
    }

    @Test
    @DisplayName("Operator endpoints enforce authorization token when security is enabled")
    void operatorEndpointsEnforceSecurityWhenEnabled() throws Exception {
        String id = createAndApproveWorkflow();
        securityProperties.setEnabled(true);
        securityProperties.setOperatorToken("secret-operator-pass");

        // Unauthorized request returns 401
        mockMvc.perform(get("/api/v1/workflows/" + id + "/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // Invalid token returns 401
        mockMvc.perform(get("/api/v1/workflows/" + id + "/evidence")
                        .header("X-Auth-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // Valid X-Auth-Token returns 200 OK
        mockMvc.perform(get("/api/v1/workflows/" + id + "/history")
                        .header("X-Auth-Token", "secret-operator-pass"))
                .andExpect(status().isOk());

        // Valid Bearer Authorization returns 200 OK
        mockMvc.perform(get("/api/v1/workflows/" + id + "/summary")
                        .header("Authorization", "Bearer secret-operator-pass"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Secrets and tokens are never leaked in operator response bodies")
    void secretsNeverLeakedInOperatorResponses() throws Exception {
        String id = createAndApproveWorkflow();

        String secretToken = "super-confidential-token-98765";
        securityProperties.setEnabled(true);
        securityProperties.setOperatorToken(secretToken);
        securityProperties.setApprovalToken(secretToken);

        // Check history response
        MvcResult historyResult = mockMvc.perform(get("/api/v1/workflows/" + id + "/history")
                        .header("X-Auth-Token", secretToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(historyResult.getResponse().getContentAsString()).doesNotContain(secretToken);

        // Check evidence response
        MvcResult evidenceResult = mockMvc.perform(get("/api/v1/workflows/" + id + "/evidence")
                        .header("X-Auth-Token", secretToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(evidenceResult.getResponse().getContentAsString()).doesNotContain(secretToken);

        // Check summary response
        MvcResult summaryResult = mockMvc.perform(get("/api/v1/workflows/" + id + "/summary")
                        .header("X-Auth-Token", secretToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(summaryResult.getResponse().getContentAsString()).doesNotContain(secretToken);
    }

    @Test
    @DisplayName("Restart-safe database persistence enables reading history and evidence after memory wipe")
    void restartSafeDatabasePersistenceServesEndpointsAfterMemoryWipe() throws Exception {
        String id = createAndApproveWorkflow();

        // Simulate application restart by wiping in-memory cache
        workflowRepository.clearMemoryCache();

        // History endpoint succeeds by rehydrating from H2
        mockMvc.perform(get("/api/v1/workflows/" + id + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(id))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.totalEvents", greaterThan(0)));

        // Evidence endpoint succeeds by rehydrating from H2
        mockMvc.perform(get("/api/v1/workflows/" + id + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(id))
                .andExpect(jsonPath("$.criteriaEvidence", not(empty())))
                .andExpect(jsonPath("$.verificationStatus.specialistAnalysis").value("COMPLETED"));

        // Summary endpoint succeeds by rehydrating from H2
        mockMvc.perform(get("/api/v1/workflows/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(id))
                .andExpect(jsonPath("$.taskCount", greaterThan(0)));
    }

    @Test
    @DisplayName("Error responses and evidence payloads do not leak local filesystem paths or secrets")
    void errorResponsesAndPayloadsDoNotLeakLocalPathsOrSecrets() throws Exception {
        // 1. Submit request with directory traversal path
        String invalidPayload = """
                {
                  "requirement": "Investigate brownfield repo",
                  "repositoryPath": "../outside-root-escape"
                }
                """;

        MvcResult errorResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andReturn();

        String errorContent = errorResult.getResponse().getContentAsString();
        assertThat(errorContent).doesNotContain("/Users/");
        assertThat(errorContent).doesNotContain("/home/");
        assertThat(errorContent).doesNotContain("/var/");
        assertThat(errorContent).doesNotContain("dev-operator-token");
        assertThat(errorContent).doesNotContain("dev-approval-token");

        // 2. Submit unauthorized request with bad token
        securityProperties.setEnabled(true);
        MvcResult unauthResult = mockMvc.perform(get("/api/v1/workflows/nonexistent-id/history")
                        .header("X-Auth-Token", "invalid-dev-operator-token"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String unauthContent = unauthResult.getResponse().getContentAsString();
        assertThat(unauthContent).doesNotContain("invalid-dev-operator-token");
        assertThat(unauthContent).doesNotContain("/Users/");
        assertThat(unauthContent).doesNotContain("/home/");
    }
}
