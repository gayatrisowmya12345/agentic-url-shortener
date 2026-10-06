package com.linkforge.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWorkflowRequest(
        @NotBlank(message = "Requirement cannot be blank")
        String requirement
) {}
