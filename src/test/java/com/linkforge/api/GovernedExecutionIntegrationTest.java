package com.linkforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GovernedExecutionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Milestone 10 End-to-End: Propose, approve, safely execute in isolated workspace, and verify build")
    void successfulEndToEndGovernedExecution() throws Exception {
        // Step 1: Create workflow
        String createPayload = """
                {
                  "requirement": "Build a URL shortener with custom alias validation supporting alphanumeric characters and minimum length 4"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/workflows/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andReturn();

        JsonNode createNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String workflowId = createNode.get("id").asText();

        // Step 2: Propose implementation
        MvcResult proposeResult = mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/propose")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.currentStage").value("IMPLEMENTATION_PROPOSAL"))
                .andExpect(jsonPath("$.implementationProposal").exists())
                .andExpect(jsonPath("$.implementationProposal.supported").value(true))
                .andExpect(jsonPath("$.implementationProposal.scope").value("ALIAS_VALIDATION"))
                .andExpect(jsonPath("$.implementationProposal.changes", hasSize(greaterThan(0))))
                .andReturn();

        JsonNode proposeNode = objectMapper.readTree(proposeResult.getResponse().getContentAsString());
        String proposalPlanHash = proposeNode.get("planHash").asText();

        // Step 3: Verify GET /proposal endpoint
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/proposal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.scope").value("ALIAS_VALIDATION"))
                .andExpect(jsonPath("$.changes[0].path").value("src/main/java/com/linkforge/service/link/AliasValidator.java"))
                .andExpect(jsonPath("$.changes[1].path").value("src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java"));

        // Step 4: Human Approval Gate
        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "security-architect@linkforge.io",
                  "comments": "Approved for governed isolated execution"
                }
                """, proposalPlanHash);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approval.decision").value("APPROVED"))
                .andExpect(jsonPath("$.approval.approver").value("security-architect@linkforge.io"));

        // Step 5: Governed Execution in Isolated Workspace
        String executePayload = String.format("""
                {
                  "planHash": "%s"
                }
                """, proposalPlanHash);

        mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.stage").value("FINISHED"))
                .andExpect(jsonPath("$.appliedChanges", hasSize(2)))
                .andExpect(jsonPath("$.appliedChanges[0].path").value("src/main/java/com/linkforge/service/link/AliasValidator.java"))
                .andExpect(jsonPath("$.appliedChanges[1].path").value("src/test/java/com/linkforge/service/link/CustomAliasValidationTest.java"))
                .andExpect(jsonPath("$.buildValidation.status").value("SUCCESS"))
                .andExpect(jsonPath("$.buildValidation.exitCode").value(0))
                .andExpect(jsonPath("$.buildValidation.durationMs", greaterThan(0)));

        // Step 6: Verify GET /execution endpoint
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.buildValidation.status").value("SUCCESS"));

        // Step 7: Verify Evidence Accounting distinguishes verified from unsupported
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus.sourceCodeGeneration").value("VERIFIED (ISOLATED_PROPOSAL)"))
                .andExpect(jsonPath("$.verificationStatus.buildExecution").value("VERIFIED (MAVEN_WRAPPER_BUILD)"))
                .andExpect(jsonPath("$.verificationStatus.automatedTestExecution").value("VERIFIED (TARGETED_TEST_EXECUTION)"))
                .andExpect(jsonPath("$.verificationStatus.deploymentAndRelease").value("NOT_SUPPORTED"));

        // Step 8: Verify auditable history events
        mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", not(empty())))
                .andExpect(jsonPath("$.events[?(@.eventType == 'IMPLEMENTATION_PROPOSED')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType == 'PLAN_APPROVED')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType == 'APPLICATION_STARTED')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType == 'APPLICATION_COMPLETED')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType == 'VALIDATION_STARTED')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType == 'VALIDATION_COMPLETED')]").exists())
                .andExpect(jsonPath("$.events[?(@.eventType == 'WORKFLOW_COMPLETED')]").exists());
    }

    @Test
    @DisplayName("Execution before human approval is rejected with 409 Conflict")
    void executeWithoutApprovalReturnsConflict() throws Exception {
        String createPayload = """
                {
                  "requirement": "Create a URL shortener with token policy constraints"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        String id = node.get("id").asText();
        String planHash = node.get("planHash").asText();

        String executePayload = String.format("""
                {
                  "planHash": "%s"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));
    }

    @Test
    @DisplayName("Execution with mismatched plan hash returns 400 Bad Request")
    void executeWithMismatchedPlanHashReturnsBadRequest() throws Exception {
        String createPayload = """
                {
                  "requirement": "Create a URL shortener with click analytics filtering"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String id = createNode.get("id").asText();

        // Propose
        MvcResult proposeResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/propose")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode propNode = objectMapper.readTree(proposeResult.getResponse().getContentAsString());
        String validPlanHash = propNode.get("planHash").asText();

        // Approve
        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "architect"
                }
                """, validPlanHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk());

        // Execute with wrong hash
        String wrongHashPayload = """
                {
                  "planHash": "tampered-or-mismatched-hash"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + id + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongHashPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PLAN_HASH"));
    }

    @Test
    @DisplayName("Caller-supplied arbitrary patch or execution result in body is rejected with 400")
    void executeWithArbitraryPayloadRejected() throws Exception {
        String payload = """
                {
                  "planHash": "some-hash",
                  "arbitraryPatch": "rm -rf /",
                  "claimedEvidence": "trust-me"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/dummy-id/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNRECOGNIZED_PROPERTY"));
    }

    @Test
    @DisplayName("Execution on unsupported requirement scope returns 409 Conflict")
    void executeUnsupportedScopeReturnsConflict() throws Exception {
        String createPayload = """
                {
                  "requirement": "Develop a complex video stream transcoding service"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String id = createNode.get("id").asText();

        // Propose -> supported is false
        MvcResult propResult = mockMvc.perform(post("/api/v1/workflows/" + id + "/propose")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.implementationProposal.supported").value(false))
                .andReturn();

        JsonNode propNode = objectMapper.readTree(propResult.getResponse().getContentAsString());
        String planHash = propNode.get("planHash").asText();
        String approvePayload = String.format("""
                {
                  "decision": "APPROVED",
                  "planHash": "%s",
                  "approver": "architect"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk());

        // Attempt execute
        String executePayload = String.format("""
                {
                  "planHash": "%s"
                }
                """, planHash);

        mockMvc.perform(post("/api/v1/workflows/" + id + "/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(executePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));
    }
}
