package com.linkforge.api.dto;

public record StopWorkflowRequest(
        String reason,
        String requestedBy
) {}
