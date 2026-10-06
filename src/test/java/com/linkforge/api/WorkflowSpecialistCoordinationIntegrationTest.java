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
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowSpecialistCoordinationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CodebaseInspectionProperties inspectionProperties;

    @TempDir
    Path tempDir;

    private Path approvedRoot;

    @BeforeEach
    void setUp() throws IOException {
        approvedRoot = tempDir.resolve("approved-root");
        Files.createDirectories(approvedRoot);
        inspectionProperties.setApprovedRoot(approvedRoot.toString());
    }

    @Test
    @DisplayName("Greenfield workflow completes specialist coordination with all tasks COMPLETED and invocations exposed")
    void greenfieldWorkflowExecutesSpecialistCoordination() throws Exception {
        String payload = """
                {
                  "requirement": "Build a greenfield URL shortener service with Base62 encoding and click tracking"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.scenario").value("GREENFIELD"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.planHash").isNotEmpty())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"))
                .andExpect(jsonPath("$.tasks", hasSize(5)))
                .andExpect(jsonPath("$.tasks[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks[2].status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks[3].status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks[4].status").value("COMPLETED"))
                .andExpect(jsonPath("$.specialistInvocations", hasSize(5)))
                .andExpect(jsonPath("$.specialistInvocations[0].role").value("DATA_PERSISTENCE"))
                .andExpect(jsonPath("$.specialistInvocations[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.specialistInvocations[0].outputSummary").isNotEmpty())
                .andExpect(jsonPath("$.specialistInvocations[0].recommendations").isNotEmpty())
                .andExpect(jsonPath("$.specialistInvocations[0].testIdeas").isNotEmpty())
                .andExpect(jsonPath("$.specialistInvocations[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$.specialistInvocations[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.events[*].eventType", hasItem("COORDINATION_STARTED")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("SPECIALIST_TASK_COMPLETED")))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("COORDINATION_COMPLETED")))
                .andReturn();

        // Verify GET /api/v1/workflows/{id} returns full coordination state
        mockMvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.specialistInvocations", hasSize(5)));
    }

    @Test
    @DisplayName("Brownfield workflow coordinates specialist tasks informed by repository evidence")
    void brownfieldWorkflowExecutesSpecialistCoordinationWithEvidence() throws Exception {
        Path repo = approvedRoot.resolve("brownfield-repo");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("pom.xml"), "<project><artifactId>service</artifactId></project>");

        String payload = """
                {
                  "requirement": "Refactor existing repository to add custom alias support",
                  "repositoryPath": "brownfield-repo"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenario").value("BROWNFIELD"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_APPROVAL"))
                .andExpect(jsonPath("$.currentStage").value("PLAN_APPROVAL"))
                .andExpect(jsonPath("$.planHash").isNotEmpty())
                .andReturn();

        String id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String planHash = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("planHash").asText();

        mockMvc.perform(post("/api/v1/workflows/" + id + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"decision\": \"APPROVED\", \"planHash\": \"%s\"}", planHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks", hasSize(3)))
                .andExpect(jsonPath("$.tasks[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks[2].status").value("COMPLETED"))
                .andExpect(jsonPath("$.specialistInvocations", hasSize(3)))
                .andExpect(jsonPath("$.events[*].eventType", hasItem("COORDINATION_COMPLETED")));
    }

    @Test
    @DisplayName("Ambiguous workflow pauses in WAITING_FOR_CLARIFICATION without specialist coordination")
    void ambiguousWorkflowPausesWithoutSpecialistExecution() throws Exception {
        String payload = """
                {
                  "requirement": "make links faster and safer"
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_CLARIFICATION"))
                .andExpect(jsonPath("$.tasks", empty()))
                .andExpect(jsonPath("$.specialistInvocations", empty()));
    }

    @Test
    @DisplayName("URL-shortener API functions cleanly with H2 persistence alongside workflow engine")
    void urlShortenerApiFunctionsAlongsideWorkflows() throws Exception {
        String createLink = """
                {
                  "destinationUrl": "https://example.com/milestone5-test",
                  "customAlias": "m5-spec-alias"
                }
                """;

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createLink))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customAlias").value("m5-spec-alias"))
                .andExpect(jsonPath("$.destinationUrl").value("https://example.com/milestone5-test"));

        mockMvc.perform(get("/r/m5-spec-alias"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/milestone5-test"));

        mockMvc.perform(get("/api/v1/links/m5-spec-alias/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(1))
                .andExpect(jsonPath("$.recentClicks", hasSize(1)));
    }
}
