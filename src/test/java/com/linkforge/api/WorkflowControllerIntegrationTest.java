package com.linkforge.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.empty;
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
class WorkflowControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/v1/workflows creates and completes workflow for clear requirement")
    void createWorkflowClearPath() throws Exception {
        String payload = """
                {
                  "requirement": "Build a high-performance URL shortener with token generation and redirection"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/workflows/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("FINISHED"))
                .andExpect(jsonPath("$.acceptanceCriteria", not(empty())))
                .andExpect(jsonPath("$.tasks", hasSize(5)))
                .andExpect(jsonPath("$.tasks[0].taskId").value("TASK-1"))
                .andExpect(jsonPath("$.tasks[1].dependencies[0]").value("TASK-1"))
                .andExpect(jsonPath("$.events", not(empty())))
                .andExpect(jsonPath("$.agentDecisions", hasSize(2)))
                .andReturn();

        // Extract ID and test GET endpoint
        String responseBody = result.getResponse().getContentAsString();
        String id = responseBody.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/workflows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /api/v1/workflows pauses in WAITING_FOR_CLARIFICATION for ambiguous requirement")
    void createWorkflowAmbiguousPath() throws Exception {
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
                .andExpect(jsonPath("$.currentStage").value("REQUIREMENT_INTERPRETATION"))
                .andExpect(jsonPath("$.unansweredQuestions", not(empty())))
                .andExpect(jsonPath("$.tasks", empty()))
                .andExpect(jsonPath("$.agentDecisions", hasSize(1)));
    }

    @Test
    @DisplayName("POST /api/v1/workflows with empty requirement returns 400 Bad Request")
    void createWorkflowEmptyRequirement() throws Exception {
        String payload = """
                {
                  "requirement": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/workflows/{id} returns 404 for unknown workflow ID")
    void getWorkflowNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/workflows/unknown-id-12345"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/health returns service health status")
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.message").value("LinkForge is running"));
    }

    @Test
    @DisplayName("GET /actuator/health returns actuator UP status")
    void actuatorHealthReturnsOk() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
