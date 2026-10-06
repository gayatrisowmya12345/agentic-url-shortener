package com.linkforge.api;

import com.linkforge.api.dto.CreateWorkflowRequest;
import com.linkforge.api.dto.WorkflowResponse;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.service.WorkflowOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowOrchestrator orchestrator;

    public WorkflowController(WorkflowOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowRun run = orchestrator.startWorkflow(request.requirement());
        URI location = URI.create("/api/v1/workflows/" + run.getId());
        return ResponseEntity.created(location).body(WorkflowResponse.from(run));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowResponse> getWorkflow(@PathVariable String id) {
        return orchestrator.getWorkflowRun(id)
                .map(run -> ResponseEntity.ok(WorkflowResponse.from(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
