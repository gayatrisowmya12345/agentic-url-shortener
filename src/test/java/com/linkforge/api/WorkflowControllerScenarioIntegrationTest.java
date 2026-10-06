package com.linkforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.service.inspection.CodebaseInspectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowControllerScenarioIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodebaseInspectionProperties inspectionProperties;

    @TempDir
    Path testApprovedRoot;

    @BeforeEach
    void setUp() {
        inspectionProperties.setApprovedRoot(testApprovedRoot.toString());
    }

    @Test
    @DisplayName("POST /api/v1/workflows executes GREENFIELD request without repository path")
    void createGreenfieldWorkflow() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener service with Base62 encoding"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.scenario").value("GREENFIELD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"))
                .andExpect(jsonPath("$.tasks", hasSize(5)))
                .andExpect(jsonPath("$.classificationDecision.agentName").value("scenario-classifier"))
                .andExpect(jsonPath("$.classificationDecision.decision").value("GREENFIELD"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows executes BROWNFIELD request with inspected repository fixture")
    void createBrownfieldWorkflowWithRepo() throws Exception, IOException {
        Path fixtureRepo = testApprovedRoot.resolve("brownfield-api-fixture");
        Files.createDirectories(fixtureRepo.resolve("src"));
        Files.writeString(fixtureRepo.resolve("pom.xml"), "<project><artifactId>fixture</artifactId></project>");

        String payload = """
                {
                  "requirement": "Refactor existing codebase to use H2 database",
                  "repositoryPath": "brownfield-api-fixture"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenario").value("BROWNFIELD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.repositoryPath").value("brownfield-api-fixture"))
                .andExpect(jsonPath("$.repositorySummary", notNullValue()))
                .andExpect(jsonPath("$.repositoryEvidence.projectFileNames", hasItem("pom.xml")))
                .andExpect(jsonPath("$.tasks", hasSize(3)))
                .andExpect(jsonPath("$.classificationDecision.decision").value("BROWNFIELD"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows pauses in WAITING_FOR_CLARIFICATION when brownfield request lacks repository path")
    void createBrownfieldWithoutRepoPauses() throws Exception {
        String payload = """
                {
                  "requirement": "Refactor existing codebase to add custom alias support"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenario").value("BROWNFIELD"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andExpect(jsonPath("$.currentStage").value("CODEBASE_INSPECTION"))
                .andExpect(jsonPath("$.unansweredQuestions", not(empty())))
                .andExpect(jsonPath("$.tasks", empty()));
    }

    @Test
    @DisplayName("POST /api/v1/workflows fails when repository path attempts traversal outside approved root")
    void createBrownfieldPathTraversalFails() throws Exception {
        String payload = """
                {
                  "requirement": "Refactor existing repository",
                  "repositoryPath": "../escape-test"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.currentStage").value("CODEBASE_INSPECTION"))
                .andExpect(jsonPath("$.tasks", empty()))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("INSPECTION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/workflows fails when repository path is absolute")
    void createBrownfieldAbsolutePathFails() throws Exception {
        String payload = """
                {
                  "requirement": "Refactor existing repository",
                  "repositoryPath": "/var/folders/absolute/not-allowed"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.currentStage").value("CODEBASE_INSPECTION"))
                .andExpect(jsonPath("$.tasks", empty()))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("INSPECTION_FAILED")));
    }

    @Test
    @DisplayName("POST /api/v1/workflows/{id}/clarifications unblocks ambiguous workflow to completion")
    void submitClarificationUnblocksAmbiguousWorkflow() throws Exception {
        // 1. Create ambiguous workflow
        String initialPayload = """
                {
                  "requirement": "make links faster and safer"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andReturn();

        String responseJson = createResult.getResponse().getContentAsString();
        String id = objectMapper.readTree(responseJson).get("id").asText();

        // 2. Submit clarification
        String clarifyPayload = """
                {
                  "clarification": "Build a new greenfield REST service for URL shortening with HTTP 302 redirects"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarifyPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.scenario").value("GREENFIELD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"))
                .andExpect(jsonPath("$.tasks", hasSize(5)));

        // 3. Verify subsequent GET returns completed run
        mockMvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows/{id}/clarifications returns 409 Conflict if workflow is already completed")
    void submitClarificationToCompletedWorkflowConflict() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        String clarifyPayload = """
                {
                  "clarification": "Extra unneeded info"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/" + id + "/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarifyPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_WORKFLOW_STATE"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows/{id}/clarifications returns 404 for unknown workflow ID")
    void submitClarificationUnknownIdReturns404() throws Exception {
        String clarifyPayload = """
                {
                  "clarification": "Some clarification"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows/unknown-id-9999/clarifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarifyPayload))
                .andExpect(status().isNotFound());
    }
}
