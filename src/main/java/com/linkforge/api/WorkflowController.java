package com.linkforge.api;

import com.linkforge.api.dto.CreateWorkflowRequest;
import com.linkforge.api.dto.StopWorkflowRequest;
import com.linkforge.api.dto.SubmitApprovalRequest;
import com.linkforge.api.dto.SubmitClarificationRequest;
import com.linkforge.api.dto.WorkflowResponse;
import com.linkforge.domain.workflow.WorkflowRun;
import com.linkforge.service.WorkflowOrchestrator;
import com.linkforge.service.security.WorkflowAuthorizationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowOrchestrator orchestrator;
    private final WorkflowAuthorizationService authorizationService;

    public WorkflowController(
            WorkflowOrchestrator orchestrator,
            WorkflowAuthorizationService authorizationService
    ) {
        this.orchestrator = orchestrator;
        this.authorizationService = authorizationService;
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowRun run = orchestrator.startWorkflow(request.requirement(), request.repositoryPath());
        URI location = URI.create("/api/v1/workflows/" + run.getId());
        return ResponseEntity.created(location).body(WorkflowResponse.from(run));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowResponse> getWorkflow(@PathVariable String id) {
        return orchestrator.getWorkflowRun(id)
                .map(run -> ResponseEntity.ok(WorkflowResponse.from(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping({ "/{id}/clarifications", "/{id}/clarify" })
    public ResponseEntity<WorkflowResponse> submitClarification(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Auth-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorHeader,
            @Valid @RequestBody SubmitClarificationRequest request
    ) {
        authorizationService.authorizeClarification(authHeader, tokenHeader);
        String submitter = authorizationService.resolveSubmitter(actorHeader, request.submittedBy());
        return orchestrator.submitClarification(id, request.clarification(), request.repositoryPath(), submitter)
                .map(run -> ResponseEntity.ok(WorkflowResponse.from(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping({ "/{id}/approve", "/{id}/approvals" })
    public ResponseEntity<WorkflowResponse> approvePlan(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Auth-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorHeader,
            @Valid @RequestBody SubmitApprovalRequest request
    ) {
        authorizationService.authorizePlanApproval(authHeader, tokenHeader);
        String approver = authorizationService.resolveApprover(actorHeader, request.approver());
        return orchestrator.approvePlan(id, request.decision(), request.planHash(), approver, request.comments())
                .map(run -> ResponseEntity.ok(WorkflowResponse.from(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping({ "/{id}/cancel", "/{id}/stop" })
    public ResponseEntity<WorkflowResponse> cancelWorkflow(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Auth-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorHeader,
            @RequestBody(required = false) StopWorkflowRequest request
    ) {
        authorizationService.authorizeCancellation(authHeader, tokenHeader);
        String bodyRequester = request != null ? request.requestedBy() : null;
        String reason = request != null ? request.reason() : null;
        String canceller = authorizationService.resolveCanceller(actorHeader, bodyRequester);

        return orchestrator.cancelWorkflow(id, canceller, reason)
                .map(run -> ResponseEntity.ok(WorkflowResponse.from(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
