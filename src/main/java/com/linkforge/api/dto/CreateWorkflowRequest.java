package com.linkforge.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWorkflowRequest(
        @NotBlank(message = "Requirement cannot be blank")
        String requirement,
        String repositoryPath
) {
    public CreateWorkflowRequest(String requirement) {
        this(requirement, null);
    }
}
